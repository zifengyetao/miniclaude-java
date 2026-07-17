package com.miniclaude.infrastructure.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 持久化 Memory 记忆系统。
 *
 * <p>职责：基于文件的 4 类记忆（user / feedback / project / reference），
 * 维护 MEMORY.md 索引，并通过 sideQuery 语义召回相关记忆注入对话。
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层，
 * 由 {@link Agent} 在对话轮次中 prefetch/注入，由 {@link Prompt} 写入 system 说明。
 * 对应 Claude Code 的 memory 架构。
 */
public final class Memory {

    private Memory() {}

    /** 语义记忆选择的 side query 回调；由 Agent 实现 LLM 调用。 */
    @FunctionalInterface
    public interface SideQueryFn {
        String apply(String system, String userMessage) throws Exception;
    }

    /** 合法的记忆类型集合。 */
    public static final Set<String> VALID_TYPES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "user", "feedback", "project", "reference")));

    public static final int MAX_INDEX_LINES = 200;
    public static final int MAX_INDEX_BYTES = 25000;
    public static final int MAX_MEMORY_FILES = 200;
    public static final int MAX_MEMORY_BYTES_PER_FILE = 4096;
    public static final int MAX_SESSION_MEMORY_BYTES = 60 * 1024;

    private static final Pattern CJK_PATTERN =
            Pattern.compile("[\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]");
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*\\}");

    /** 语义选择 side query 的 system prompt 模板。 */
    public static final String SELECT_MEMORIES_PROMPT =
            "You are selecting memories that will be useful to an AI coding assistant as it processes a user's query. "
                    + "You will be given the user's query and a list of available memory files with their filenames and descriptions.\n\n"
                    + "Return a JSON object with a \"selected_memories\" array of filenames for the memories that will clearly be useful (up to 5). "
                    + "Only include memories that you are certain will be helpful based on their name and description.\n"
                    + "- If you are unsure if a memory will be useful, do not include it.\n"
                    + "- If no memories would clearly be useful, return an empty array.";

    // ─── 数据类型 ───────────────────────────────────────────────

    /** 完整记忆条目（含 frontmatter 与正文）。 */
    public static final class MemoryEntry {
        public final String name;
        public final String description;
        public final String type;
        public final String filename;
        public final String content;

        public MemoryEntry(String name, String description, String type, String filename, String content) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.filename = filename;
            this.content = content;
        }
    }

    /** 记忆文件轻量头信息（扫描 frontmatter 前 30 行）。 */
    public static final class MemoryHeader {
        public final String filename;
        public final String filePath;
        public final double mtimeMs;
        public final String description;
        public final String type;

        public MemoryHeader(String filename, String filePath, double mtimeMs,
                            String description, String type) {
            this.filename = filename;
            this.filePath = filePath;
            this.mtimeMs = mtimeMs;
            this.description = description;
            this.type = type;
        }
    }

    /** 语义召回后待注入的相关记忆。 */
    public static final class RelevantMemory {
        public final String path;
        public final String content;
        public final double mtimeMs;
        public final String header;

        public RelevantMemory(String path, String content, double mtimeMs, String header) {
            this.path = path;
            this.content = content;
            this.mtimeMs = mtimeMs;
            this.header = header;
        }
    }

    /**
     * 记忆 prefetch 句柄：先 {@link #isSettled()} 再消费 {@link #future}。
     */
    public static final class MemoryPrefetch {
        public final CompletableFuture<List<RelevantMemory>> future;
        public volatile boolean consumed;

        public MemoryPrefetch(CompletableFuture<List<RelevantMemory>> future) {
            this.future = future;
            this.consumed = false;
        }

        /** future 是否已完成（成功或失败）。 */
        public boolean isSettled() {
            return future != null && future.isDone();
        }
    }

    // ─── 路径与目录 ─────────────────────────────────────────────

    private static String projectHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Paths.get("").toAbsolutePath().normalize().toString()
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (Exception e) {
            return "0000000000000000";
        }
    }

    /** 返回当前项目对应的 memory 目录（按 cwd 哈希隔离）。 */
    public static Path getMemoryDir() {
        Path d = Paths.get(System.getProperty("user.home"), ".mini-claude", "projects",
                projectHash(), "memory");
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create memory dir: " + d, e);
        }
        return d;
    }

    private static Path getIndexPath() {
        return getMemoryDir().resolve("MEMORY.md");
    }

    // ─── Slug 生成（private）────────────────────────────────────

    private static String slugify(String text) {
        String s = text.toLowerCase().replaceAll("[^a-z0-9]+", "_");
        s = s.replaceAll("^_+|_+$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40);
        }
        return s;
    }

    // ─── CRUD ───────────────────────────────────────────────────

    /** 列出所有有效记忆条目，按修改时间降序。 */
    public static List<MemoryEntry> listMemories() {
        Path d = getMemoryDir();
        List<MemoryEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(d)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("MEMORY.md"))
                    .sorted()
                    .collect(Collectors.toList());
            for (Path f : files) {
                try {
                    String raw = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                    Frontmatter.FrontmatterResult result = Frontmatter.parseFrontmatter(raw);
                    Map<String, String> meta = result.meta;
                    if (meta.get("name") == null || meta.get("name").isEmpty()
                            || meta.get("type") == null || meta.get("type").isEmpty()) {
                        continue;
                    }
                    String t = VALID_TYPES.contains(meta.get("type")) ? meta.get("type") : "project";
                    entries.add(new MemoryEntry(
                            meta.get("name"),
                            meta.getOrDefault("description", ""),
                            t,
                            f.getFileName().toString(),
                            result.body
                    ));
                } catch (Exception ignored) {
                    // skip corrupt files
                }
            }
        } catch (IOException ignored) {
            return entries;
        }
        entries.sort((a, b) -> {
            try {
                long ma = Files.getLastModifiedTime(d.resolve(a.filename)).toMillis();
                long mb = Files.getLastModifiedTime(d.resolve(b.filename)).toMillis();
                return Long.compare(mb, ma);
            } catch (IOException e) {
                return 0;
            }
        });
        return entries;
    }

    /** 保存记忆文件并自动更新 MEMORY.md 索引；返回生成的文件名。 */
    public static String saveMemory(String name, String description, String type, String content) {
        Path d = getMemoryDir();
        String filename = type + "_" + slugify(name) + ".md";
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("description", description);
        meta.put("type", type);
        String text = Frontmatter.formatFrontmatter(meta, content);
        try {
            Files.write(d.resolve(filename), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save memory: " + filename, e);
        }
        updateMemoryIndex();
        return filename;
    }

    /** 删除指定记忆文件并更新索引；文件不存在时返回 {@code false}。 */
    public static boolean deleteMemory(String filename) {
        Path filepath = getMemoryDir().resolve(filename);
        if (!Files.exists(filepath)) {
            return false;
        }
        try {
            Files.delete(filepath);
        } catch (IOException e) {
            return false;
        }
        updateMemoryIndex();
        return true;
    }

    // ─── MEMORY.md 索引 ───────────────────────────────────────────

    private static void updateMemoryIndex() {
        List<MemoryEntry> memories = listMemories();
        List<String> lines = new ArrayList<>();
        lines.add("# Memory Index");
        lines.add("");
        for (MemoryEntry m : memories) {
            lines.add("- **[" + m.name + "](" + m.filename + ")** (" + m.type + ") — " + m.description);
        }
        try {
            Files.write(getIndexPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Failed to update memory index", e);
        }
    }

    /** 加载 MEMORY.md 索引内容（超长时截断）。 */
    public static String loadMemoryIndex() {
        Path indexPath = getIndexPath();
        if (!Files.exists(indexPath)) {
            return "";
        }
        try {
            String content = new String(Files.readAllBytes(indexPath), StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            if (lines.length > MAX_INDEX_LINES) {
                content = String.join("\n", Arrays.copyOf(lines, MAX_INDEX_LINES))
                        + "\n\n[... truncated, too many memory entries ...]";
            }
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_INDEX_BYTES) {
                // Truncate by char count approximating Python content[:MAX_INDEX_BYTES]
                content = content.substring(0, Math.min(content.length(), MAX_INDEX_BYTES))
                        + "\n\n[... truncated, index too large ...]";
            }
            return content;
        } catch (IOException e) {
            return "";
        }
    }

    // ─── 轻量扫描（header only）──────────────────────────────────

    /** 扫描 memory 目录，返回各 .md 文件的轻量头信息。 */
    public static List<MemoryHeader> scanMemoryHeaders() {
        Path d = getMemoryDir();
        List<MemoryHeader> headers = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(d, "*.md")) {
            for (Path f : stream) {
                if (f.getFileName().toString().equals("MEMORY.md")) {
                    continue;
                }
                try {
                    long mtimeMs = Files.getLastModifiedTime(f).toMillis();
                    String raw = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                    String[] allLines = raw.split("\n", -1);
                    String first30 = String.join("\n",
                            Arrays.copyOf(allLines, Math.min(30, allLines.length)));
                    Frontmatter.FrontmatterResult result = Frontmatter.parseFrontmatter(first30);
                    Map<String, String> meta = result.meta;
                    String t = meta.get("type");
                    headers.add(new MemoryHeader(
                            f.getFileName().toString(),
                            f.toAbsolutePath().normalize().toString(),
                            mtimeMs,
                            meta.get("description"),
                            (t != null && VALID_TYPES.contains(t)) ? t : null
                    ));
                } catch (Exception ignored) {
                    // skip corrupt files
                }
            }
        } catch (IOException ignored) {
            return headers;
        }
        headers.sort(Comparator.comparingDouble((MemoryHeader h) -> h.mtimeMs).reversed());
        if (headers.size() > MAX_MEMORY_FILES) {
            return new ArrayList<>(headers.subList(0, MAX_MEMORY_FILES));
        }
        return headers;
    }

    /** 将 header 列表格式化为供 LLM 选择的 manifest 文本。 */
    public static String formatMemoryManifest(List<MemoryHeader> headers) {
        List<String> lines = new ArrayList<>();
        for (MemoryHeader h : headers) {
            String tag = h.type != null ? "[" + h.type + "] " : "";
            String ts = Instant.ofEpochMilli((long) h.mtimeMs).toString();
            if (h.description != null && !h.description.isEmpty()) {
                lines.add("- " + tag + h.filename + " (" + ts + "): " + h.description);
            } else {
                lines.add("- " + tag + h.filename + " (" + ts + ")");
            }
        }
        return String.join("\n", lines);
    }

    // ─── 记忆时效 ───────────────────────────────────────────────

    /** 返回人类可读的记忆保存时长（today / yesterday / N days ago）。 */
    public static String memoryAge(double mtimeMs) {
        int days = Math.max(0, (int) ((System.currentTimeMillis() - mtimeMs) / 86_400_000L));
        if (days == 0) {
            return "today";
        }
        if (days == 1) {
            return "yesterday";
        }
        return days + " days ago";
    }

    /** 超过 1 天的记忆返回 freshness 警告文案；否则返回空串。 */
    public static String memoryFreshnessWarning(double mtimeMs) {
        int days = Math.max(0, (int) ((System.currentTimeMillis() - mtimeMs) / 86_400_000L));
        if (days <= 1) {
            return "";
        }
        return "This memory is " + days + " days old. Memories are point-in-time observations, "
                + "not live state — claims about code behavior may be outdated. "
                + "Verify against current code before asserting as fact.";
    }

    // ─── 语义召回（sideQuery）────────────────────────────────────

    /**
     * 通过 sideQuery LLM 从候选记忆中选出与 query 最相关的条目（最多 5 条）。
     */
    public static List<RelevantMemory> selectRelevantMemories(
            String query,
            SideQueryFn sideQuery,
            Set<String> alreadySurfaced
    ) {
        List<MemoryHeader> headers = scanMemoryHeaders();
        if (headers.isEmpty()) {
            return Collections.emptyList();
        }

        List<MemoryHeader> candidates = new ArrayList<>();
        for (MemoryHeader h : headers) {
            if (!alreadySurfaced.contains(h.filePath)) {
                candidates.add(h);
            }
        }
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        String manifest = formatMemoryManifest(candidates);
        try {
            String text = sideQuery.apply(
                    SELECT_MEMORIES_PROMPT,
                    "Query: " + query + "\n\nAvailable memories:\n" + manifest
            );

            Matcher match = JSON_OBJECT_PATTERN.matcher(text);
            if (!match.find()) {
                return Collections.emptyList();
            }

            JsonObject parsed = JsonParser.parseString(match.group(0)).getAsJsonObject();
            Set<String> selectedFilenames = new HashSet<>();
            if (parsed.has("selected_memories") && parsed.get("selected_memories").isJsonArray()) {
                JsonArray arr = parsed.getAsJsonArray("selected_memories");
                for (JsonElement el : arr) {
                    selectedFilenames.add(el.getAsString());
                }
            }

            List<MemoryHeader> selected = new ArrayList<>();
            for (MemoryHeader h : candidates) {
                if (selectedFilenames.contains(h.filename)) {
                    selected.add(h);
                    if (selected.size() >= 5) {
                        break;
                    }
                }
            }

            List<RelevantMemory> result = new ArrayList<>();
            for (MemoryHeader h : selected) {
                String content = new String(Files.readAllBytes(Paths.get(h.filePath)),
                        StandardCharsets.UTF_8);
                if (content.getBytes(StandardCharsets.UTF_8).length > MAX_MEMORY_BYTES_PER_FILE) {
                    content = content.substring(0, Math.min(content.length(), MAX_MEMORY_BYTES_PER_FILE))
                            + "\n\n[... truncated, memory file too large ...]";
                }
                String freshness = memoryFreshnessWarning(h.mtimeMs);
                String headerText = (freshness != null && !freshness.isEmpty())
                        ? freshness + "\n\nMemory: " + h.filePath + ":"
                        : "Memory (saved " + memoryAge(h.mtimeMs) + "): " + h.filePath + ":";
                result.add(new RelevantMemory(h.filePath, content, h.mtimeMs, headerText));
            }
            return result;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("cancel")) {
                return Collections.emptyList();
            }
            System.out.println("[memory] semantic recall failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─── Prefetch 异步预取 ────────────────────────────────────────

    /**
     * 对用户 query 启动异步记忆 prefetch；query 过短或已达 session 上限时返回 {@code null}。
     */
    public static MemoryPrefetch startMemoryPrefetch(
            String query,
            SideQueryFn sideQuery,
            Set<String> alreadySurfaced,
            int sessionMemoryBytes
    ) {
        String stripped = query == null ? "" : query.trim();
        Matcher cjk = CJK_PATTERN.matcher(stripped);
        int cjkCount = 0;
        while (cjk.find()) {
            cjkCount++;
        }
        if (cjkCount < 2 && !Pattern.compile("\\s").matcher(stripped).find()) {
            return null;
        }

        if (sessionMemoryBytes >= MAX_SESSION_MEMORY_BYTES) {
            return null;
        }

        Path d = getMemoryDir();
        boolean hasMemories = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(d)) {
            for (Path f : stream) {
                String name = f.getFileName().toString();
                if (name.endsWith(".md") && !name.equals("MEMORY.md")) {
                    hasMemories = true;
                    break;
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (!hasMemories) {
            return null;
        }

        Set<String> surfacedCopy = new HashSet<>(alreadySurfaced);
        CompletableFuture<List<RelevantMemory>> future = CompletableFuture.supplyAsync(() ->
                selectRelevantMemories(query, sideQuery, surfacedCopy));
        return new MemoryPrefetch(future);
    }

    /** 将相关记忆格式化为 {@code <system-reminder>} 注入块。 */
    public static String formatMemoriesForInjection(List<RelevantMemory> memories) {
        List<String> parts = new ArrayList<>();
        for (RelevantMemory m : memories) {
            parts.add("<system-reminder>\n" + m.header + "\n\n" + m.content + "\n</system-reminder>");
        }
        return String.join("\n\n", parts);
    }

    // ─── 系统提示词片段 ───────────────────────────────────────────

    /** 构建注入 system prompt 的 Memory 使用说明与当前索引。 */
    public static String buildMemoryPromptSection() {
        String index = loadMemoryIndex();
        String memoryDir = getMemoryDir().toString();

        String indexSection = (index != null && !index.isEmpty())
                ? "\n## Current Memory Index\n" + index
                : "\n(No memories saved yet.)";

        return "# Memory System\n"
                + "\n"
                + "You have a persistent, file-based memory system at `" + memoryDir + "`.\n"
                + "\n"
                + "## Memory Types\n"
                + "- **user**: User's role, preferences, knowledge level\n"
                + "- **feedback**: Corrections and guidance from the user (include Why + How to apply)\n"
                + "- **project**: Ongoing work, goals, deadlines, decisions\n"
                + "- **reference**: Pointers to external resources (URLs, tools, dashboards)\n"
                + "\n"
                + "## How to Save Memories\n"
                + "Use the write_file tool to create a memory file with YAML frontmatter:\n"
                + "\n"
                + "```markdown\n"
                + "---\n"
                + "name: memory name\n"
                + "description: one-line description\n"
                + "type: user|feedback|project|reference\n"
                + "---\n"
                + "Memory content here.\n"
                + "```\n"
                + "\n"
                + "Save to: `" + memoryDir + "/`\n"
                + "Filename format: `{type}_{slugified_name}.md`\n"
                + "\n"
                + "The MEMORY.md index is auto-updated when you write to the memory directory — do NOT update it manually.\n"
                + "\n"
                + "## What NOT to Save\n"
                + "- Code patterns or architecture (read the code instead)\n"
                + "- Git history (use git log)\n"
                + "- Anything already in CLAUDE.md\n"
                + "- Ephemeral task details\n"
                + "\n"
                + "## When to Recall\n"
                + "When the user asks you to remember or recall, or when prior context seems relevant.\n"
                + indexSection;
    }
}
