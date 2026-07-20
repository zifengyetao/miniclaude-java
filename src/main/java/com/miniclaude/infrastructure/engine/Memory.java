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
 * 持久化 Memory（记忆）系统 —— 基于本地文件的多类型长期记忆存储与语义召回。
 *
 * <p><b>核心职责</b></p>
 * <ul>
 *   <li>在 {@code ~/.mini-claude/projects/{hash}/memory/} 目录下维护 4 类记忆文件：
 *       {@code user}（用户偏好）、{@code feedback}（用户纠正）、
 *       {@code project}（项目上下文）、{@code reference}（外部引用）。</li>
 *   <li>自动生成并维护 {@code MEMORY.md} 索引，供 Agent 在 system prompt 中展示当前记忆概览。</li>
 *   <li>通过 {@link SideQueryFn} 回调发起「side query」LLM 调用，从候选记忆中语义筛选与当前 query 最相关的条目，
 *       并以 {@code <system-reminder>} 块注入对话上下文。</li>
 *   <li>支持 {@link MemoryPrefetch} 异步预取，在用户输入较长时提前启动召回，降低主链路延迟。</li>
 * </ul>
 *
 * <p><b>在系统中的位置</b></p>
 * <ul>
 *   <li>包路径：{@code infrastructure/engine} —— 引擎层基础设施，不依赖 Spring 容器。</li>
 *   <li>调用方：{@link Agent} 在对话轮次中 prefetch/注入记忆；{@link Prompt} 通过
 *       {@link #buildMemoryPromptSection()} 将记忆使用说明写入 system prompt。</li>
 *   <li>设计参考：Claude Code 的 memory 架构（文件 + frontmatter + 语义召回）。</li>
 * </ul>
 *
 * <p><b>容量与安全边界</b></p>
 * <ul>
 *   <li>索引行数/字节、单文件大小、会话累计注入字节均有上限常量约束，防止 context 膨胀。</li>
 *   <li>超过 1 天的记忆会附加 freshness 警告，提醒 Agent 验证内容是否仍与代码一致。</li>
 * </ul>
 *
 * <p>本类为工具类，禁止实例化。</p>
 *
 * @see Agent
 * @see Prompt
 * @see Frontmatter
 */
public final class Memory {

    /** 私有构造器：工具类不可实例化。 */
    private Memory() {}

    /**
     * 语义记忆选择的 side query 回调函数式接口。
     *
     * <p>由 {@link Agent} 实现，内部封装一次轻量级 LLM 调用（system + userMessage），
     * 返回模型原始文本响应（期望为含 {@code selected_memories} 字段的 JSON）。</p>
     */
    @FunctionalInterface
    public interface SideQueryFn {
        /**
         * 执行 side query LLM 调用。
         *
         * @param system      系统提示词（通常为 {@link #SELECT_MEMORIES_PROMPT}）
         * @param userMessage 用户侧消息（含 query 与记忆 manifest）
         * @return LLM 原始文本响应
         * @throws Exception LLM 调用失败或网络异常时抛出
         * @sideeffects 可能产生外部 API 调用与 token 消耗
         */
        String apply(String system, String userMessage) throws Exception;
    }

    /** 合法的记忆类型集合（不可变）：user / feedback / project / reference。 */
    public static final Set<String> VALID_TYPES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "user", "feedback", "project", "reference")));

    /** MEMORY.md 索引允许的最大行数；超出时截断并附加提示。 */
    public static final int MAX_INDEX_LINES = 200;

    /** MEMORY.md 索引允许的最大字节数（UTF-8）；超出时截断并附加提示。 */
    public static final int MAX_INDEX_BYTES = 25000;

    /** 扫描/召回时最多考虑的 .md 记忆文件数量（不含 MEMORY.md）。 */
    public static final int MAX_MEMORY_FILES = 200;

    /** 单条记忆文件注入时的最大字节数（UTF-8）；超出时截断正文。 */
    public static final int MAX_MEMORY_BYTES_PER_FILE = 4096;

    /** 单个会话累计注入记忆内容的最大字节数；达到上限后不再 prefetch。 */
    public static final int MAX_SESSION_MEMORY_BYTES = 60 * 1024;

    /** 中日韩（CJK）及日韩字符检测正则，用于判断 query 是否「足够长」以触发 prefetch。 */
    private static final Pattern CJK_PATTERN =
            Pattern.compile("[\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]");

    /** 从 LLM 响应文本中提取 JSON 对象的正则（贪婪匹配首个 {...} 块）。 */
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*\\}");

    /**
     * 语义选择 side query 的 system prompt 模板（英文）。
     *
     * <p>指示 LLM 从 manifest 中选出最多 5 条「明确有用」的记忆文件名，
     * 以 JSON {@code {"selected_memories": ["file1.md", ...]}} 形式返回。</p>
     */
    public static final String SELECT_MEMORIES_PROMPT =
            "You are selecting memories that will be useful to an AI coding assistant as it processes a user's query. "
                    + "You will be given the user's query and a list of available memory files with their filenames and descriptions.\n\n"
                    + "Return a JSON object with a \"selected_memories\" array of filenames for the memories that will clearly be useful (up to 5). "
                    + "Only include memories that you are certain will be helpful based on their name and description.\n"
                    + "- If you are unsure if a memory will be useful, do not include it.\n"
                    + "- If no memories would clearly be useful, return an empty array.";

    // ─── 数据类型 ───────────────────────────────────────────────

    /**
     * 完整记忆条目：解析 frontmatter 后的结构化表示（含正文 body）。
     *
     * <p>由 {@link #listMemories()} 从磁盘 .md 文件构建；用于 CRUD 与索引生成。</p>
     */
    public static final class MemoryEntry {
        /** 记忆显示名称（frontmatter {@code name} 字段）。 */
        public final String name;
        /** 一行描述（frontmatter {@code description} 字段，可为空串）。 */
        public final String description;
        /** 记忆类型：user / feedback / project / reference（非法值会被归一化为 project）。 */
        public final String type;
        /** 磁盘文件名，如 {@code user_my_preference.md}。 */
        public final String filename;
        /** frontmatter 之后的 Markdown 正文内容。 */
        public final String content;

        /**
         * 构造完整记忆条目。
         *
         * @param name        记忆名称
         * @param description 描述
         * @param type        类型
         * @param filename    文件名
         * @param content     正文
         */
        public MemoryEntry(String name, String description, String type, String filename, String content) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.filename = filename;
            this.content = content;
        }
    }

    /**
     * 记忆文件轻量头信息：仅扫描 frontmatter 前 30 行，不加载全文。
     *
     * <p>用于 {@link #scanMemoryHeaders()} 与语义召回 manifest，降低 I/O 与 token 开销。</p>
     */
    public static final class MemoryHeader {
        /** 文件名（不含路径）。 */
        public final String filename;
        /** 绝对规范化文件路径，用作去重键（{@code alreadySurfaced}）。 */
        public final String filePath;
        /** 最后修改时间（毫秒 epoch）。 */
        public final double mtimeMs;
        /** frontmatter 中的 description，可能为 {@code null}。 */
        public final String description;
        /** 合法 type 或 {@code null}（frontmatter 缺失/非法时）。 */
        public final String type;

        /**
         * 构造记忆头信息。
         *
         * @param filename    文件名
         * @param filePath    绝对路径
         * @param mtimeMs     修改时间毫秒
         * @param description 描述
         * @param type        类型
         */
        public MemoryHeader(String filename, String filePath, double mtimeMs,
                            String description, String type) {
            this.filename = filename;
            this.filePath = filePath;
            this.mtimeMs = mtimeMs;
            this.description = description;
            this.type = type;
        }
    }

    /**
     * 语义召回后待注入的相关记忆：含路径、全文（可能截断）、时效头文案。
     */
    public static final class RelevantMemory {
        /** 记忆文件绝对路径。 */
        public final String path;
        /** 文件全文或截断后的内容。 */
        public final String content;
        /** 最后修改时间（毫秒），用于 freshness 计算。 */
        public final double mtimeMs;
        /** 注入块顶部说明行（含 freshness 警告或 saved 时间）。 */
        public final String header;

        /**
         * 构造相关记忆。
         *
         * @param path     文件路径
         * @param content  正文
         * @param mtimeMs  修改时间
         * @param header   注入头文案
         */
        public RelevantMemory(String path, String content, double mtimeMs, String header) {
            this.path = path;
            this.content = content;
            this.mtimeMs = mtimeMs;
            this.header = header;
        }
    }

    /**
     * 记忆 prefetch 句柄：包装异步 {@link CompletableFuture} 与消费标记。
     *
     * <p>使用约定：先 {@link #isSettled()} 确认 future 已完成，再读取 {@link #future} 结果；
     * {@link #consumed} 由调用方标记是否已注入，避免重复 surfacing。</p>
     */
    public static final class MemoryPrefetch {
        /** 异步语义召回任务；完成后为 {@link List}{@code <}{@link RelevantMemory}{@code >}。 */
        public final CompletableFuture<List<RelevantMemory>> future;
        /** 是否已被消费（注入对话）；volatile 供多线程可见。 */
        public volatile boolean consumed;

        /**
         * 创建 prefetch 句柄。
         *
         * @param future 已启动的异步召回 future
         * @sideeffects 初始化 {@code consumed = false}
         */
        public MemoryPrefetch(CompletableFuture<List<RelevantMemory>> future) {
            this.future = future;
            this.consumed = false;
        }

        /**
         * 判断 future 是否已 settled（正常完成或异常完成）。
         *
         * @return {@code true} 当 future 非 null 且 {@code isDone()}
         */
        public boolean isSettled() {
            return future != null && future.isDone();
        }
    }

    // ─── 路径与目录 ─────────────────────────────────────────────

    /**
     * 根据当前工作目录（cwd）计算 16 位十六进制项目哈希，用于隔离不同项目的 memory 目录。
     *
     * @return SHA-256 前 16 个 hex 字符；算法不可用时返回 16 个 {@code '0'}
     * @sideeffects 无
     */
    private static String projectHash() {
        try {
            // 对规范化后的绝对 cwd 字符串做 SHA-256
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Paths.get("").toAbsolutePath().normalize().toString()
                    .getBytes(StandardCharsets.UTF_8));
            // 转为小写 hex 字符串
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (Exception e) {
            // 降级：固定占位哈希，仍可使用 memory 功能
            return "0000000000000000";
        }
    }

    /**
     * 返回当前项目对应的 memory 根目录，并在不存在时递归创建。
     *
     * <p>路径模式：{@code ~/.mini-claude/projects/{projectHash}/memory/}</p>
     *
     * @return memory 目录 {@link Path}
     * @throws RuntimeException 创建目录失败时
     * @sideeffects 可能创建 {@code ~/.mini-claude/.../memory} 目录树
     */
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

    /**
     * 返回 MEMORY.md 索引文件的绝对路径。
     *
     * @return 索引文件 Path
     * @sideeffects 间接调用 {@link #getMemoryDir()}，可能创建 memory 目录
     */
    private static Path getIndexPath() {
        return getMemoryDir().resolve("MEMORY.md");
    }

    // ─── Slug 生成（private）────────────────────────────────────

    /**
     * 将任意文本转为文件名安全的 slug：小写、非字母数字替换为下划线、首尾去下划线、最长 40 字符。
     *
     * @param text 原始名称（如记忆 name）
     * @return slug 片段，用于 {@code {type}_{slug}.md}
     * @sideeffects 无
     */
    private static String slugify(String text) {
        // 转小写并将连续非 [a-z0-9] 替换为单个 _
        String s = text.toLowerCase().replaceAll("[^a-z0-9]+", "_");
        // 去掉首尾 _
        s = s.replaceAll("^_+|_+$", "");
        // 限制长度避免文件名过长
        if (s.length() > 40) {
            s = s.substring(0, 40);
        }
        return s;
    }

    // ─── CRUD ───────────────────────────────────────────────────

    /**
     * 列出 memory 目录下所有有效 .md 记忆条目（排除 MEMORY.md），按修改时间降序。
     *
     * <p>无效文件（缺少 name/type、解析失败）会被静默跳过。</p>
     *
     * @return 记忆条目列表，可能为空；I/O 失败时返回已收集部分或空列表
     * @sideeffects 读取 memory 目录下所有 .md 文件
     */
    public static List<MemoryEntry> listMemories() {
        Path d = getMemoryDir();
        List<MemoryEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(d)) {
            // 筛选 .md 且非索引文件，按文件名字典序预排序（后续再按 mtime 重排）
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
                    // 必须有非空 name 与 type 才视为有效记忆
                    if (meta.get("name") == null || meta.get("name").isEmpty()
                            || meta.get("type") == null || meta.get("type").isEmpty()) {
                        continue;
                    }
                    // 非法 type 归一化为 project
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
        // 按文件最后修改时间降序（新的在前）
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

    /**
     * 保存或覆盖一条记忆文件，并自动刷新 MEMORY.md 索引。
     *
     * <p>文件名规则：{@code {type}_{slugify(name)}.md}；内容含 YAML frontmatter + body。</p>
     *
     * @param name        记忆名称
     * @param description 一行描述
     * @param type        记忆类型（应在 {@link #VALID_TYPES} 内）
     * @param content     Markdown 正文
     * @return 写入的文件名
     * @throws RuntimeException 磁盘写入失败
     * @sideeffects 写入 .md 文件；调用 {@link #updateMemoryIndex()} 重写 MEMORY.md
     */
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

    /**
     * 删除指定记忆文件并更新索引。
     *
     * @param filename 要删除的文件名（如 {@code user_prefs.md}）
     * @return {@code true} 删除成功；文件不存在或 I/O 失败返回 {@code false}
     * @sideeffects 可能删除文件并刷新 MEMORY.md
     */
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

    /**
     * 根据 {@link #listMemories()} 结果重写 MEMORY.md 索引（Markdown 链接列表）。
     *
     * @throws RuntimeException 写入索引失败
     * @sideeffects 覆盖 {@code MEMORY.md} 文件内容
     */
    private static void updateMemoryIndex() {
        List<MemoryEntry> memories = listMemories();
        List<String> lines = new ArrayList<>();
        lines.add("# Memory Index");
        lines.add("");
        // 每条记忆一行：名称链接 + 类型 + 描述
        for (MemoryEntry m : memories) {
            lines.add("- **[" + m.name + "](" + m.filename + ")** (" + m.type + ") — " + m.description);
        }
        try {
            Files.write(getIndexPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Failed to update memory index", e);
        }
    }

    /**
     * 加载 MEMORY.md 索引内容；文件不存在或读失败返回空串；超长时按行数/字节截断。
     *
     * @return 索引 Markdown 文本（可能含截断提示）
     * @sideeffects 读取 MEMORY.md（若存在）
     */
    public static String loadMemoryIndex() {
        Path indexPath = getIndexPath();
        if (!Files.exists(indexPath)) {
            return "";
        }
        try {
            String content = new String(Files.readAllBytes(indexPath), StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            // 行数上限截断
            if (lines.length > MAX_INDEX_LINES) {
                content = String.join("\n", Arrays.copyOf(lines, MAX_INDEX_LINES))
                        + "\n\n[... truncated, too many memory entries ...]";
            }
            // 字节上限截断（按 char 近似，与 Python 行为对齐）
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

    /**
     * 扫描 memory 目录，返回各 .md 文件的轻量头信息（仅读前 30 行解析 frontmatter）。
     *
     * <p>结果按 mtime 降序，最多 {@link #MAX_MEMORY_FILES} 条。</p>
     *
     * @return 头信息列表
     * @sideeffects 读取目录下最多 MAX_MEMORY_FILES 个 .md 文件的前 30 行
     */
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
                    // 仅取前 30 行用于 frontmatter 解析
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
        // 按修改时间降序
        headers.sort(Comparator.comparingDouble((MemoryHeader h) -> h.mtimeMs).reversed());
        if (headers.size() > MAX_MEMORY_FILES) {
            return new ArrayList<>(headers.subList(0, MAX_MEMORY_FILES));
        }
        return headers;
    }

    /**
     * 将 {@link MemoryHeader} 列表格式化为供 LLM 选择的 manifest 纯文本（每行一条记忆摘要）。
     *
     * @param headers 头信息列表
     * @return 多行 manifest 字符串
     * @sideeffects 无
     */
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

    /**
     * 将修改时间转为人类可读的记忆「年龄」英文短语。
     *
     * @param mtimeMs 最后修改时间（毫秒）
     * @return {@code "today"} / {@code "yesterday"} / {@code "{N} days ago"}
     * @sideeffects 无
     */
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

    /**
     * 若记忆超过 1 天，返回英文 freshness 警告文案；否则返回空串。
     *
     * <p>警告提醒 Agent：记忆是时点观察，可能与当前代码不一致，需验证后再断言。</p>
     *
     * @param mtimeMs 最后修改时间（毫秒）
     * @return 警告段落或 {@code ""}
     * @sideeffects 无
     */
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
     * 通过 sideQuery LLM 从候选记忆中选出与 query 最相关的条目（最多 5 条），并加载全文构建 {@link RelevantMemory}。
     *
     * <p>流程：扫描 headers → 排除 {@code alreadySurfaced} → 格式化 manifest →
     * sideQuery → 解析 JSON {@code selected_memories} → 读文件并截断 → 附加 header。</p>
     *
     * @param query           用户当前 query
     * @param sideQuery       LLM 回调
     * @param alreadySurfaced 本会话已注入过的记忆绝对路径集合
     * @return 相关记忆列表；无候选、解析失败、取消时返回空列表
     * @sideeffects 可能调用 sideQuery（外部 LLM）；读取被选中的记忆文件；失败时向 stdout 打印日志
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

        // 过滤本会话已 surface 过的路径
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
            // 调用 side query LLM 选择文件名
            String text = sideQuery.apply(
                    SELECT_MEMORIES_PROMPT,
                    "Query: " + query + "\n\nAvailable memories:\n" + manifest
            );

            // 从响应中提取 JSON 对象
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

            // 将文件名映射回 MemoryHeader，最多 5 条
            List<MemoryHeader> selected = new ArrayList<>();
            for (MemoryHeader h : candidates) {
                if (selectedFilenames.contains(h.filename)) {
                    selected.add(h);
                    if (selected.size() >= 5) {
                        break;
                    }
                }
            }

            // 加载全文并构建 RelevantMemory
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
            // 用户取消类异常：静默返回空
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("cancel")) {
                return Collections.emptyList();
            }
            System.out.println("[memory] semantic recall failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─── Prefetch 异步预取 ────────────────────────────────────────

    /**
     * 对用户 query 启动异步记忆 prefetch；不满足前置条件时返回 {@code null}（不启动任务）。
     *
     * <p>跳过条件：query 过短（CJK&lt;2 且无空格）、会话记忆字节达上限、目录无 .md 记忆文件。</p>
     *
     * @param query              用户 query
     * @param sideQuery          LLM 回调
     * @param alreadySurfaced    已 surface 路径集合
     * @param sessionMemoryBytes 当前会话已注入记忆累计字节
     * @return {@link MemoryPrefetch} 或 {@code null}
     * @sideeffects 可能在线程池中异步执行 {@link #selectRelevantMemories}
     */
    public static MemoryPrefetch startMemoryPrefetch(
            String query,
            SideQueryFn sideQuery,
            Set<String> alreadySurfaced,
            int sessionMemoryBytes
    ) {
        String stripped = query == null ? "" : query.trim();
        // 统计 CJK 字符数，过短 query 不做 prefetch
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

        // 快速检查是否存在至少一个非索引 .md 文件
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

        // 复制 surfaced 集合供异步线程使用
        Set<String> surfacedCopy = new HashSet<>(alreadySurfaced);
        CompletableFuture<List<RelevantMemory>> future = CompletableFuture.supplyAsync(() ->
                selectRelevantMemories(query, sideQuery, surfacedCopy));
        return new MemoryPrefetch(future);
    }

    /**
     * 将相关记忆列表格式化为可注入对话的 {@code <system-reminder>} 块（多条之间空行分隔）。
     *
     * @param memories {@link RelevantMemory} 列表
     * @return 拼接后的注入文本；空列表返回空串
     * @sideeffects 无
     */
    public static String formatMemoriesForInjection(List<RelevantMemory> memories) {
        List<String> parts = new ArrayList<>();
        for (RelevantMemory m : memories) {
            parts.add("<system-reminder>\n" + m.header + "\n\n" + m.content + "\n</system-reminder>");
        }
        return String.join("\n\n", parts);
    }

    // ─── 系统提示词片段 ───────────────────────────────────────────

    /**
     * 构建注入 system prompt 的 Memory 子章节：使用说明、类型定义、保存规范与当前 MEMORY.md 索引。
     *
     * @return 完整 Markdown 段落（含 memory 目录绝对路径）
     * @sideeffects 调用 {@link #loadMemoryIndex()} 与 {@link #getMemoryDir()}
     */
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
