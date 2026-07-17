package com.miniclaude.infrastructure.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 工具定义与执行。
 *
 * <p>职责：注册 Agent 可用的工具 schema（read/write/shell/web/agent 等），
 * 执行工具调用、权限校验、deferred tool 激活及 read-before-edit 保护。
 * {@code agent} 与 {@code skill} 工具由 {@link Agent} 处理以避免循环依赖。
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层，
 * 被 {@link Agent} 在每轮 tool call 时调用。对应 Python {@code tools.py} 移植。
 */
public final class Tools {

    private Tools() {}

    // ─── 权限模式常量 ───────────────────────────────────────────

    /** Agent 权限模式字符串常量。 */
    public static final class PermissionMode {
        public static final String DEFAULT = "default";
        public static final String PLAN = "plan";
        public static final String ACCEPT_EDITS = "acceptEdits";
        public static final String BYPASS_PERMISSIONS = "bypassPermissions";
        public static final String DONT_ASK = "dontAsk";
        public static final String AUTO = "auto";

        private PermissionMode() {}
    }

    /** 只读类工具（plan 模式等场景默认放行）。 */
    public static final Set<String> READ_TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "read_file", "list_files", "grep_search", "web_fetch", "web_search"
    )));

    /** 文件编辑类工具。 */
    public static final Set<String> EDIT_TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "write_file", "edit_file"
    )));

    /** 可并行执行的安全工具（只读、无副作用）。 */
    public static final Set<String> CONCURRENCY_SAFE_TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "read_file", "list_files", "grep_search", "web_fetch", "web_search"
    )));

    private static final boolean IS_WIN = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson GSON_COMPACT = new Gson();

    // ─── 工具 schema 定义 ─────────────────────────────────────────

    /** 全部工具定义（含 deferred 标记）；启动时构建为不可变列表。 */
    public static final List<Map<String, Object>> TOOL_DEFINITIONS = buildToolDefinitions();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buildToolDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>();

        defs.add(tool("read_file",
                "Read the contents of a file. Returns the file content with line numbers.",
                schema(props(
                        prop("file_path", "string", "The path to the file to read")
                ), "file_path"),
                false));

        defs.add(tool("write_file",
                "Write content to a file. Creates the file if it doesn't exist, overwrites if it does.",
                schema(props(
                        prop("file_path", "string", "The path to the file to write"),
                        prop("content", "string", "The content to write to the file")
                ), "file_path", "content"),
                false));

        defs.add(tool("edit_file",
                "Edit a file by replacing an exact string match with new content. The old_string must match exactly (including whitespace and indentation).",
                schema(props(
                        prop("file_path", "string", "The path to the file to edit"),
                        prop("old_string", "string", "The exact string to find and replace"),
                        prop("new_string", "string", "The string to replace it with")
                ), "file_path", "old_string", "new_string"),
                false));

        defs.add(tool("list_files",
                "List files matching a glob pattern. Returns matching file paths.",
                schema(props(
                        prop("pattern", "string", "Glob pattern to match files (e.g., \"**/*.ts\", \"src/**/*\")"),
                        prop("path", "string", "Base directory to search from. Defaults to current directory.")
                ), "pattern"),
                false));

        defs.add(tool("grep_search",
                "Search for a pattern in files. Returns matching lines with file paths and line numbers.",
                schema(props(
                        prop("pattern", "string", "The regex pattern to search for"),
                        prop("path", "string", "Directory or file to search in. Defaults to current directory."),
                        prop("include", "string", "File glob pattern to include (e.g., \"*.ts\", \"*.py\")")
                ), "pattern"),
                false));

        Map<String, Object> timeoutProp = prop("timeout", "number", "Timeout in milliseconds (default: 30000)");
        defs.add(tool("run_shell",
                "Execute a shell command and return its output. Use this for running tests, installing packages, git operations, etc.",
                schema(props(
                        prop("command", "string", "The shell command to execute"),
                        timeoutProp
                ), "command"),
                false));

        defs.add(tool("skill",
                "Invoke a registered skill by name. Skills are prompt templates loaded from .claude/skills/. Returns the skill's resolved prompt to follow.",
                schema(props(
                        prop("skill_name", "string", "The name of the skill to invoke"),
                        prop("args", "string", "Optional arguments to pass to the skill")
                ), "skill_name"),
                false));

        Map<String, Object> maxLenProp = prop("max_length", "number", "Maximum content length in characters (default 50000)");
        defs.add(tool("web_search",
                "Search the public web for up-to-date information. Returns ranked results with title, URL, and snippet. "
                        + "Use this when you do not already know the exact URL (news, sales stats, docs, etc.). "
                        + "After searching, call web_fetch on the most relevant URLs to read full page content before answering.",
                schema(props(
                        prop("query", "string", "Search query in natural language (Chinese or English)"),
                        prop("max_results", "number", "Max results to return (default 5, max 10)")
                ), "query"),
                false));

        defs.add(tool("web_fetch",
                "Fetch a known URL and return its content as text. For HTML pages, tags are stripped to return readable text. "
                        + "For JSON/text responses, content is returned directly. Prefer web_search first when you need to discover URLs.",
                schema(props(
                        prop("url", "string", "The URL to fetch"),
                        maxLenProp
                ), "url"),
                false));

        defs.add(tool("enter_plan_mode",
                "Enter plan mode to switch to a read-only planning phase. In plan mode, you can only read files and write to the plan file.",
                schema(props(), new String[0]),
                true));

        defs.add(tool("exit_plan_mode",
                "Exit plan mode after you have finished writing your plan to the plan file.",
                schema(props(), new String[0]),
                true));

        Map<String, Object> typeProp = new HashMap<>();
        typeProp.put("type", "string");
        typeProp.put("enum", Arrays.asList("explore", "plan", "general"));
        typeProp.put("description", "Agent type. Default: general");
        Map<String, Object> agentProps = props(
                prop("description", "string", "Short (3-5 word) description of the sub-agent's task"),
                prop("prompt", "string", "Detailed task instructions for the sub-agent")
        );
        agentProps.put("type", typeProp);
        defs.add(tool("agent",
                "Launch a sub-agent to handle a task autonomously. Sub-agents have isolated context and return their result. Types: 'explore' (read-only), 'plan' (read-only, structured planning), 'general' (full tools).",
                schema(agentProps, "description", "prompt"),
                false));

        defs.add(tool("tool_search",
                "Search for available tools by name or keyword. Returns full schema definitions for matching deferred tools so you can use them.",
                schema(props(
                        prop("query", "string", "Tool name or search keywords")
                ), "query"),
                false));

        return Collections.unmodifiableList(defs);
    }

    private static Map<String, Object> tool(String name, String description,
                                            Map<String, Object> inputSchema, boolean deferred) {
        Map<String, Object> t = new HashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("input_schema", inputSchema);
        if (deferred) {
            t.put("deferred", true);
        }
        return t;
    }

    @SafeVarargs
    private static Map<String, Object> props(Map<String, Object>... entries) {
        Map<String, Object> m = new HashMap<>();
        for (Map<String, Object> e : entries) {
            // each entry is a single-key map from prop()
            m.putAll(e);
        }
        return m;
    }

    private static Map<String, Object> prop(String name, String type, String description) {
        Map<String, Object> field = new HashMap<>();
        field.put("type", type);
        field.put("description", description);
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put(name, field);
        return wrapper;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && required.length > 0) {
            schema.put("required", Arrays.asList(required));
        }
        return schema;
    }

    // ─── Deferred 工具按需激活 ────────────────────────────────────

    private static final Set<String> activatedTools = Collections.synchronizedSet(new HashSet<>());

    /** 重置已激活的 deferred 工具集合（测试用）。 */
    public static void resetActivatedTools() {
        activatedTools.clear();
    }

    /**
     * Return tool definitions, excluding deferred tools that haven't been activated.
     * Strips the 'deferred' key so it's not sent to the API.
     */
    /** 返回当前应暴露给 LLM 的工具定义（非 deferred 或已激活）。 */
    public static List<Map<String, Object>> getActiveToolDefinitions(List<Map<String, Object>> allTools) {
        List<Map<String, Object>> tools = allTools != null ? allTools : TOOL_DEFINITIONS;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            boolean deferred = Boolean.TRUE.equals(t.get("deferred"));
            String name = String.valueOf(t.get("name"));
            if (!deferred || activatedTools.contains(name)) {
                Map<String, Object> copy = new HashMap<>();
                for (Map.Entry<String, Object> e : t.entrySet()) {
                    if (!"deferred".equals(e.getKey())) {
                        copy.put(e.getKey(), e.getValue());
                    }
                }
                result.add(copy);
            }
        }
        return result;
    }

    /** 基于 {@link #TOOL_DEFINITIONS} 返回当前活跃工具定义。 */
    public static List<Map<String, Object>> getActiveToolDefinitions() {
        return getActiveToolDefinitions(null);
    }

    /** Return names of deferred tools that haven't been activated yet. */
    /** 返回仍为 deferred、未激活的工具名列表。 */
    public static List<String> getDeferredToolNames(List<Map<String, Object>> allTools) {
        List<Map<String, Object>> tools = allTools != null ? allTools : TOOL_DEFINITIONS;
        List<String> names = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            if (Boolean.TRUE.equals(t.get("deferred"))) {
                String name = String.valueOf(t.get("name"));
                if (!activatedTools.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** 基于 {@link #TOOL_DEFINITIONS} 返回 deferred 工具名。 */
    public static List<String> getDeferredToolNames() {
        return getDeferredToolNames(null);
    }

    // ─── 各工具实现（package-private）──────────────────────────────

    private static String str(Map<String, Object> inp, String key) {
        Object v = inp.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String strOr(Map<String, Object> inp, String key, String def) {
        String v = str(inp, key);
        return v == null || v.isEmpty() ? def : v;
    }

    private static Number num(Map<String, Object> inp, String key, Number def) {
        Object v = inp.get(key);
        if (v instanceof Number) {
            return (Number) v;
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    static String readFile(Map<String, Object> inp) {
        try {
            Path path = Paths.get(str(inp, "file_path"));
            byte[] bytes = Files.readAllBytes(path);
            String content = new String(bytes, StandardCharsets.UTF_8);
            // errors="replace" equivalent for malformed sequences is default replacement in Java decoder
            // when using CharsetDecoder REPLACE — String(byte[], UTF_8) replaces malformed.
            String[] lines = content.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append('\n');
                sb.append(String.format("%4d | %s", i + 1, lines[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    static String writeFile(Map<String, Object> inp) {
        try {
            Path path = Paths.get(str(inp, "file_path"));
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String content = strOr(inp, "content", "");
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
            autoUpdateMemoryIndex(path.toString());
            String[] lines = content.split("\n", -1);
            int lineCount = lines.length;
            StringBuilder preview = new StringBuilder();
            int show = Math.min(30, lineCount);
            for (int i = 0; i < show; i++) {
                if (i > 0) preview.append('\n');
                preview.append(String.format("%4d | %s", i + 1, lines[i]));
            }
            String trunc = lineCount > 30 ? "\n  ... (" + lineCount + " lines total)" : "";
            return "Successfully wrote to " + str(inp, "file_path") + " (" + lineCount + " lines)\n\n"
                    + preview + trunc;
        } catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    /** Inline memory-dir logic (matches Memory.getMemoryDir) to avoid hard dependency at compile time. */
    private static Path getMemoryDir() {
        try {
            String cwd = Paths.get("").toAbsolutePath().normalize().toString();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cwd.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            String projectHash = hex.substring(0, 16);
            Path d = Paths.get(System.getProperty("user.home"), ".mini-claude", "projects", projectHash, "memory");
            Files.createDirectories(d);
            return d;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void autoUpdateMemoryIndex(String filePath) {
        try {
            String memDir = getMemoryDir().toString();
            if (filePath.startsWith(memDir) && filePath.endsWith(".md") && !filePath.endsWith("MEMORY.md")) {
                Path memPath = Paths.get(memDir);
                List<String> lines = new ArrayList<>();
                lines.add("# Memory Index");
                lines.add("");
                try (Stream<Path> stream = Files.list(memPath)) {
                    List<Path> files = stream
                            .filter(f -> f.getFileName().toString().endsWith(".md"))
                            .filter(f -> !"MEMORY.md".equals(f.getFileName().toString()))
                            .sorted()
                            .collect(Collectors.toList());
                    Pattern namePat = Pattern.compile("^name:\\s*(.+)$", Pattern.MULTILINE);
                    Pattern typePat = Pattern.compile("^type:\\s*(.+)$", Pattern.MULTILINE);
                    Pattern descPat = Pattern.compile("^description:\\s*(.+)$", Pattern.MULTILINE);
                    for (Path f : files) {
                        try {
                            String raw = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                            Matcher nameMatch = namePat.matcher(raw);
                            Matcher typeMatch = typePat.matcher(raw);
                            Matcher descMatch = descPat.matcher(raw);
                            if (nameMatch.find() && typeMatch.find()) {
                                String n = nameMatch.group(1).trim();
                                String t = typeMatch.group(1).trim();
                                String d = descMatch.find() ? descMatch.group(1).trim() : "";
                                lines.add("- **[" + n + "](" + f.getFileName() + ")** (" + t + ") — " + d);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                Files.write(memPath.resolve("MEMORY.md"), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    // ─── 编辑辅助：引号规范化与 diff ───────────────────────────────

    private static final Pattern SMART_SINGLE = Pattern.compile("[\u2018\u2019\u2032]");
    private static final Pattern SMART_DOUBLE = Pattern.compile("[\u201c\u201d\u2033]");

    static String normalizeQuotes(String s) {
        s = SMART_SINGLE.matcher(s).replaceAll("'");
        s = SMART_DOUBLE.matcher(s).replaceAll("\"");
        return s;
    }

    static String findActualString(String fileContent, String searchString) {
        if (fileContent.contains(searchString)) {
            return searchString;
        }
        String normSearch = normalizeQuotes(searchString);
        String normFile = normalizeQuotes(fileContent);
        int idx = normFile.indexOf(normSearch);
        if (idx != -1) {
            return fileContent.substring(idx, idx + searchString.length());
        }
        return null;
    }

    static String generateDiff(String oldContent, String oldString, String newString) {
        int splitIdx = oldContent.indexOf(oldString);
        String beforeChange = splitIdx >= 0 ? oldContent.substring(0, splitIdx) : "";
        int lineNum = countNewlines(beforeChange) + 1;
        String[] oldLines = oldString.split("\n", -1);
        String[] newLines = newString.split("\n", -1);

        StringBuilder parts = new StringBuilder();
        parts.append("@@ -").append(lineNum).append(',').append(oldLines.length)
                .append(" +").append(lineNum).append(',').append(newLines.length).append(" @@");
        for (String l : oldLines) {
            parts.append('\n').append("- ").append(l);
        }
        for (String l : newLines) {
            parts.append('\n').append("+ ").append(l);
        }
        return parts.toString();
    }

    private static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    static String editFile(Map<String, Object> inp) {
        try {
            Path path = Paths.get(str(inp, "file_path"));
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            String oldString = strOr(inp, "old_string", "");
            String newString = strOr(inp, "new_string", "");

            String actual = findActualString(content, oldString);
            if (actual == null) {
                return "Error: old_string not found in " + str(inp, "file_path");
            }

            int count = countOccurrences(content, actual);
            if (count > 1) {
                return "Error: old_string found " + count + " times in " + str(inp, "file_path") + ". Must be unique.";
            }

            String newContent = content.replaceFirst(Pattern.quote(actual), Matcher.quoteReplacement(newString));
            Files.write(path, newContent.getBytes(StandardCharsets.UTF_8));

            String diff = generateDiff(content, actual, newString);
            String quoteNote = !actual.equals(oldString) ? " (matched via quote normalization)" : "";
            return "Successfully edited " + str(inp, "file_path") + quoteNote + "\n\n" + diff;
        } catch (Exception e) {
            return "Error editing file: " + e.getMessage();
        }
    }

    static String listFiles(Map<String, Object> inp) {
        try {
            Path base = Paths.get(strOr(inp, "path", ".")).toAbsolutePath().normalize();
            String pattern = str(inp, "pattern");
            if (pattern == null) {
                return "Error listing files: pattern is required";
            }

            List<String> files = new ArrayList<>();
            int[] extra = {0};

            // Normalize glob: PathMatcher expects relative paths from base
            String globPattern = pattern;
            final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

            if (!Files.isDirectory(base)) {
                return "Error listing files: base path is not a directory";
            }

            Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(base)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if ("node_modules".equals(name) || name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path rel = base.relativize(file);
                    // Skip hidden path components
                    for (Path part : rel) {
                        String p = part.toString();
                        if ("node_modules".equals(p) || p.startsWith(".")) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    boolean match = matcher.matches(rel) || matcher.matches(file.getFileName());
                    // Also try matching with forward-slash string (Windows)
                    if (!match) {
                        String relStr = rel.toString().replace('\\', '/');
                        PathMatcher m2 = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
                        match = m2.matches(Paths.get(relStr));
                    }
                    if (match && Files.isRegularFile(file)) {
                        String display = base.equals(Paths.get(".").toAbsolutePath().normalize())
                                ? file.toString()
                                : rel.toString();
                        // Prefer relative display like Python when base != "."
                        Path cwd = Paths.get(".").toAbsolutePath().normalize();
                        if (!base.equals(cwd) || str(inp, "path") != null) {
                            display = rel.toString().replace('\\', '/');
                        } else {
                            display = rel.toString().replace('\\', '/');
                            if (display.isEmpty()) {
                                display = file.getFileName().toString();
                            }
                        }
                        if (files.size() < 200) {
                            files.add(display);
                        } else {
                            extra[0]++;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (files.isEmpty()) {
                return "No files found matching the pattern.";
            }
            String result = String.join("\n", files);
            if (extra[0] > 0) {
                result += "\n... and " + extra[0] + " more";
            }
            return result;
        } catch (Exception e) {
            return "Error listing files: " + e.getMessage();
        }
    }

    static String grepSearch(Map<String, Object> inp) {
        String pattern = str(inp, "pattern");
        String path = strOr(inp, "path", ".");
        String include = str(inp, "include");

        if (!IS_WIN) {
            try {
                List<String> args = new ArrayList<>();
                args.add("grep");
                args.add("--line-number");
                args.add("--color=never");
                args.add("-r");
                if (include != null && !include.isEmpty()) {
                    args.add("--include=" + include);
                }
                args.add("--");
                args.add(pattern);
                args.add(path);

                ProcessBuilder pb = new ProcessBuilder(args);
                pb.redirectErrorStream(false);
                Process proc = pb.start();
                boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                } else {
                    int code = proc.exitValue();
                    String stdout = readStream(proc.getInputStream());
                    if (code == 1) {
                        return "No matches found.";
                    }
                    if (code == 0) {
                        String[] lines = stdout.isEmpty() ? new String[0] : stdout.split("\n", -1);
                        List<String> nonEmpty = new ArrayList<>();
                        for (String l : lines) {
                            if (!l.isEmpty()) nonEmpty.add(l);
                        }
                        StringBuilder output = new StringBuilder();
                        int limit = Math.min(100, nonEmpty.size());
                        for (int i = 0; i < limit; i++) {
                            if (i > 0) output.append('\n');
                            output.append(nonEmpty.get(i));
                        }
                        if (nonEmpty.size() > 100) {
                            output.append("\n... and ").append(nonEmpty.size() - 100).append(" more matches");
                        }
                        return output.toString();
                    }
                    // Non-zero exit (not 1) — fall through to Java fallback
                }
            } catch (Exception ignored) {
            }
        }

        return grepJava(pattern, path, include);
    }

    private static String readStream(java.io.InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append('\n');
                first = false;
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static boolean fnmatch(String name, String pattern) {
        // Convert simple glob to regex (fnmatch-like)
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append('.');
                    break;
                case '.':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '+':
                case '^':
                case '$':
                case '|':
                case '\\':
                    regex.append('\\').append(c);
                    break;
                default:
                    regex.append(c);
            }
        }
        regex.append('$');
        return name.matches(regex.toString());
    }

    static String grepJava(String pattern, String directory, String include) {
        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (Exception e) {
            return "Error: invalid regex pattern: " + e.getMessage();
        }

        List<String> matches = new ArrayList<>();
        int[] extra = {0};
        Path root = Paths.get(directory);

        walkGrep(root, regex, include, matches, extra);

        if (matches.isEmpty()) {
            return "No matches found.";
        }
        String output = String.join("\n", matches);
        if (extra[0] > 0) {
            output += "\n... and " + extra[0] + " more matches";
        }
        return output;
    }

    private static void walkGrep(Path d, Pattern regex, String includePattern,
                                 List<String> matches, int[] extra) {
        if (!Files.isDirectory(d)) {
            if (Files.isRegularFile(d)) {
                grepFile(d, regex, includePattern, matches, extra);
            }
            return;
        }
        try (Stream<Path> stream = Files.list(d)) {
            for (Path entry : stream.collect(Collectors.toList())) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") || "node_modules".equals(name)) {
                    continue;
                }
                if (Files.isDirectory(entry)) {
                    walkGrep(entry, regex, includePattern, matches, extra);
                } else {
                    grepFile(entry, regex, includePattern, matches, extra);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void grepFile(Path full, Pattern regex, String includePattern,
                                 List<String> matches, int[] extra) {
        String name = full.getFileName().toString();
        if (includePattern != null && !includePattern.isEmpty() && !fnmatch(name, includePattern)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(full);
            String text = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (regex.matcher(lines[i]).find()) {
                    if (matches.size() < 100) {
                        matches.add(full.toString() + ":" + (i + 1) + ":" + lines[i]);
                    } else {
                        extra[0]++;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    static String runShell(Map<String, Object> inp) {
        try {
            long timeoutMs = num(inp, "timeout", 30000).longValue();
            String command = str(inp, "command");
            ProcessBuilder pb;
            if (IS_WIN) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("/bin/sh", "-c", command);
            }
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "Command timed out after " + timeoutMs + "ms";
            }
            String stdout = readStream(proc.getInputStream());
            String stderr = readStream(proc.getErrorStream());
            int code = proc.exitValue();
            if (code != 0) {
                String errPart = (stderr != null && !stderr.isEmpty()) ? "\nStderr: " + stderr : "";
                String outPart = (stdout != null && !stdout.isEmpty()) ? "\nStdout: " + stdout : "";
                return "Command failed (exit code " + code + ")" + outPart + errPart;
            }
            return (stdout == null || stdout.isEmpty()) ? "(no output)" : stdout;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    static String webFetch(Map<String, Object> inp) {
        String url = strOr(inp, "url", "");
        int maxLength = num(inp, "max_length", 50000).intValue();

        if (!url.toLowerCase(Locale.ROOT).startsWith("http://")
                && !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return "Error: only http(s) URLs are supported";
        }

        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (compatible; MiniClaude/1.0; +https://github.com/miniclaude)");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code >= 400) {
                String reason = conn.getResponseMessage() != null ? conn.getResponseMessage() : "";
                return "HTTP error: " + code + " " + reason;
            }

            String contentType = conn.getContentType() != null ? conn.getContentType() : "";
            String text;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) >= 0) {
                    sb.append(buf, 0, n);
                }
                text = sb.toString();
            }

            if (contentType.toLowerCase(Locale.ROOT).contains("html")) {
                text = stripHtml(text);
            }

            if (text.length() > maxLength) {
                text = text.substring(0, maxLength) + "\n\n[... truncated at " + maxLength + " characters]";
            }

            return text.isEmpty() ? "(empty response)" : text;
        } catch (java.net.MalformedURLException e) {
            return "Error fetching " + url + ": " + e.getMessage();
        } catch (IOException e) {
            return "Error fetching " + url + ": " + e.getMessage();
        } catch (Exception e) {
            return "Error fetching " + url + ": " + e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Web search via DuckDuckGo HTML (no API key). Returns title / URL / snippet list
     * so the model can follow up with {@link #webFetch}.
     */
    static String webSearch(Map<String, Object> inp) {
        String query = strOr(inp, "query", "").trim();
        if (query.isEmpty()) {
            return "Error: query is required";
        }
        int maxResults = Math.max(1, Math.min(10, num(inp, "max_results", 5).intValue()));

        try {
            String encoded = java.net.URLEncoder.encode(query, "UTF-8");
            String url = "https://html.duckduckgo.com/html/?q=" + encoded;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(20_000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code >= 400) {
                return "Error: search HTTP " + code + " " + (conn.getResponseMessage() != null
                        ? conn.getResponseMessage() : "");
            }

            String html;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) >= 0) {
                    sb.append(buf, 0, n);
                }
                html = sb.toString();
            }
            conn.disconnect();

            List<String> results = parseDuckDuckGoResults(html, maxResults);
            if (results.isEmpty()) {
                return "No search results found for: " + query
                        + "\nTip: try a more specific query, or web_fetch a known official URL.";
            }
            StringBuilder out = new StringBuilder();
            out.append("Search results for: ").append(query).append("\n\n");
            for (int i = 0; i < results.size(); i++) {
                out.append(i + 1).append(". ").append(results.get(i)).append("\n\n");
            }
            out.append("Next: use web_fetch on the most relevant URLs above to read full content.");
            return out.toString().trim();
        } catch (Exception e) {
            return "Error searching: " + e.getMessage();
        }
    }

    private static List<String> parseDuckDuckGoResults(String html, int maxResults) {
        List<String> out = new ArrayList<>();
        // result blocks: <a class="result__a" href="...">title</a> ... <a class="result__snippet"> or <td class="result__snippet">
        Pattern linkPat = Pattern.compile(
                "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
                Pattern.CASE_INSENSITIVE);
        Pattern snippetPat = Pattern.compile(
                "class=\"result__snippet\"[^>]*>([\\s\\S]*?)</(?:a|td|span)>",
                Pattern.CASE_INSENSITIVE);

        Matcher linkM = linkPat.matcher(html);
        List<int[]> linkSpans = new ArrayList<>();
        List<String> hrefs = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (linkM.find()) {
            String href = decodeDuckRedirect(htmlDecode(linkM.group(1)));
            String title = stripHtml(linkM.group(2)).trim();
            if (href.isEmpty() || title.isEmpty()) {
                continue;
            }
            if (!href.startsWith("http://") && !href.startsWith("https://")) {
                continue;
            }
            linkSpans.add(new int[]{linkM.start(), linkM.end()});
            hrefs.add(href);
            titles.add(title);
            if (hrefs.size() >= maxResults * 2) {
                break; // collect extra, trim later
            }
        }

        Matcher snipM = snippetPat.matcher(html);
        List<int[]> snipPos = new ArrayList<>();
        List<String> snips = new ArrayList<>();
        while (snipM.find()) {
            snipPos.add(new int[]{snipM.start(), snipM.end()});
            snips.add(stripHtml(snipM.group(1)).trim());
        }

        for (int i = 0; i < hrefs.size() && out.size() < maxResults; i++) {
            String snippet = "";
            int linkEnd = linkSpans.get(i)[1];
            int nextLinkStart = (i + 1 < linkSpans.size()) ? linkSpans.get(i + 1)[0] : html.length();
            for (int s = 0; s < snipPos.size(); s++) {
                int sp = snipPos.get(s)[0];
                if (sp >= linkEnd && sp < nextLinkStart) {
                    snippet = snips.get(s);
                    break;
                }
            }
            StringBuilder item = new StringBuilder();
            item.append(titles.get(i)).append("\n");
            item.append("   URL: ").append(hrefs.get(i));
            if (!snippet.isEmpty()) {
                item.append("\n   ").append(snippet);
            }
            out.add(item.toString());
        }
        return out;
    }

    /** DuckDuckGo wraps outbound links as //duckduckgo.com/l/?uddg=<encoded>. */
    private static String decodeDuckRedirect(String href) {
        if (href == null) {
            return "";
        }
        href = href.trim();
        if (href.startsWith("//")) {
            href = "https:" + href;
        }
        try {
            if (href.contains("duckduckgo.com/l/?") || href.contains("duckduckgo.com/l?")) {
                int idx = href.indexOf("uddg=");
                if (idx >= 0) {
                    String rest = href.substring(idx + 5);
                    int amp = rest.indexOf('&');
                    if (amp >= 0) {
                        rest = rest.substring(0, amp);
                    }
                    return java.net.URLDecoder.decode(rest, "UTF-8");
                }
            }
        } catch (Exception ignored) {
        }
        return href;
    }

    private static String stripHtml(String text) {
        text = Pattern.compile("<script[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE)
                .matcher(text).replaceAll("");
        text = Pattern.compile("<style[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE)
                .matcher(text).replaceAll("");
        text = Pattern.compile("<[^>]*>").matcher(text).replaceAll(" ");
        text = htmlDecode(text);
        text = Pattern.compile("\\s{2,}").matcher(text).replaceAll(" ");
        text = Pattern.compile("\\n{3,}").matcher(text).replaceAll("\n\n");
        return text.trim();
    }

    private static String htmlDecode(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#x27;", "'")
                .replace("&#39;", "'");
    }

    // ─── 危险命令模式匹配 ─────────────────────────────────────────

    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
            Pattern.compile("\\brm\\s"),
            Pattern.compile("\\bgit\\s+(push|reset|clean|checkout\\s+\\.)"),
            Pattern.compile("\\bsudo\\b"),
            Pattern.compile("\\bmkfs\\b"),
            Pattern.compile("\\bdd\\s"),
            Pattern.compile(">\\s*/dev/"),
            Pattern.compile("\\bkill\\b"),
            Pattern.compile("\\bpkill\\b"),
            Pattern.compile("\\breboot\\b"),
            Pattern.compile("\\bshutdown\\b"),
            Pattern.compile("\\bdel\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brmdir\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bformat\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btaskkill\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bRemove-Item\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bStop-Process\\s", Pattern.CASE_INSENSITIVE)
    );

    /** 判断 shell 命令是否匹配危险模式（需用户确认）。 */
    public static boolean isDangerous(String command) {
        if (command == null) return false;
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    // ─── 权限规则（.claude/settings.json）──────────────────────────

    private static Map<String, List<Map<String, String>>> cachedRules = null;

    private static Map<String, String> parseRule(String rule) {
        Matcher m = Pattern.compile("^([a-z_]+)\\((.+)\\)$").matcher(rule);
        Map<String, String> out = new HashMap<>();
        if (m.matches()) {
            out.put("tool", m.group(1));
            out.put("pattern", m.group(2));
        } else {
            out.put("tool", rule);
            out.put("pattern", null);
        }
        return out;
    }

    private static JsonObject loadSettings(Path filePath) {
        if (!Files.exists(filePath)) {
            return null;
        }
        try {
            String text = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            JsonElement el = JsonParser.parseString(text);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 加载用户级与项目级 allow/deny 权限规则（带缓存）。 */
    public static Map<String, List<Map<String, String>>> loadPermissionRules() {
        if (cachedRules != null) {
            return cachedRules;
        }

        List<Map<String, String>> allow = new ArrayList<>();
        List<Map<String, String>> deny = new ArrayList<>();

        Path userSettings = Paths.get(System.getProperty("user.home"), ".claude", "settings.json");
        Path projectSettings = Paths.get("").toAbsolutePath().resolve(".claude").resolve("settings.json");

        for (Path settingsPath : Arrays.asList(userSettings, projectSettings)) {
            JsonObject settings = loadSettings(settingsPath);
            if (settings == null || !settings.has("permissions")) {
                continue;
            }
            JsonObject perms = settings.getAsJsonObject("permissions");
            if (perms.has("allow") && perms.get("allow").isJsonArray()) {
                for (JsonElement r : perms.getAsJsonArray("allow")) {
                    allow.add(parseRule(r.getAsString()));
                }
            }
            if (perms.has("deny") && perms.get("deny").isJsonArray()) {
                for (JsonElement r : perms.getAsJsonArray("deny")) {
                    deny.add(parseRule(r.getAsString()));
                }
            }
        }

        Map<String, List<Map<String, String>>> rules = new HashMap<>();
        rules.put("allow", allow);
        rules.put("deny", deny);
        cachedRules = rules;
        return cachedRules;
    }

    /** 清除权限规则缓存。 */
    public static void resetPermissionCache() {
        cachedRules = null;
    }

    private static boolean matchesRule(Map<String, String> rule, String toolName, Map<String, Object> inp) {
        if (!toolName.equals(rule.get("tool"))) {
            return false;
        }
        String pattern = rule.get("pattern");
        if (pattern == null) {
            return true;
        }

        String value = "";
        if ("run_shell".equals(toolName)) {
            value = strOr(inp, "command", "");
        } else if (inp.containsKey("file_path")) {
            value = strOr(inp, "file_path", "");
        } else {
            return true;
        }

        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return value.equals(pattern);
    }

    private static String checkPermissionRules(String toolName, Map<String, Object> inp) {
        Map<String, List<Map<String, String>>> rules = loadPermissionRules();
        for (Map<String, String> rule : rules.get("deny")) {
            if (matchesRule(rule, toolName, inp)) {
                return "deny";
            }
        }
        for (Map<String, String> rule : rules.get("allow")) {
            if (matchesRule(rule, toolName, inp)) {
                return "allow";
            }
        }
        return null;
    }

    /**
     * 校验工具调用权限。
     *
     * @return 含 {@code action}（allow/deny/confirm）及可选 {@code message} 的 Map
     */
    public static Map<String, String> checkPermission(
            String toolName,
            Map<String, Object> inp,
            String mode,
            String planFilePath) {

        if (mode == null) {
            mode = PermissionMode.DEFAULT;
        }

        String ruleResult = checkPermissionRules(toolName, inp);
        if ("deny".equals(ruleResult)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "deny");
            r.put("message", "Denied by permission rule for " + toolName);
            return r;
        }

        if (PermissionMode.PLAN.equals(mode)) {
            if (EDIT_TOOLS.contains(toolName)) {
                String filePath = str(inp, "file_path");
                if (filePath == null) {
                    filePath = str(inp, "path");
                }
                if (planFilePath != null && planFilePath.equals(filePath)) {
                    Map<String, String> r = new HashMap<>();
                    r.put("action", "allow");
                    return r;
                }
                Map<String, String> r = new HashMap<>();
                r.put("action", "deny");
                r.put("message", "Blocked in plan mode: " + toolName);
                return r;
            }
            if ("run_shell".equals(toolName)) {
                Map<String, String> r = new HashMap<>();
                r.put("action", "deny");
                r.put("message", "Shell commands blocked in plan mode");
                return r;
            }
        }

        if (PermissionMode.BYPASS_PERMISSIONS.equals(mode)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "allow");
            return r;
        }

        if ("allow".equals(ruleResult)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "allow");
            return r;
        }

        if (READ_TOOLS.contains(toolName)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "allow");
            return r;
        }

        if ("enter_plan_mode".equals(toolName) || "exit_plan_mode".equals(toolName)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "allow");
            return r;
        }

        if (PermissionMode.ACCEPT_EDITS.equals(mode) && EDIT_TOOLS.contains(toolName)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "allow");
            return r;
        }

        boolean needsConfirm = false;
        String confirmMessage = "";

        if ("run_shell".equals(toolName) && isDangerous(strOr(inp, "command", ""))) {
            needsConfirm = true;
            confirmMessage = strOr(inp, "command", "");
        } else if ("write_file".equals(toolName)
                && !Files.exists(Paths.get(strOr(inp, "file_path", "")))) {
            needsConfirm = true;
            confirmMessage = "write new file: " + strOr(inp, "file_path", "");
        } else if ("edit_file".equals(toolName)
                && !Files.exists(Paths.get(strOr(inp, "file_path", "")))) {
            needsConfirm = true;
            confirmMessage = "edit non-existent file: " + strOr(inp, "file_path", "");
        }

        if (needsConfirm) {
            if (PermissionMode.DONT_ASK.equals(mode)) {
                Map<String, String> r = new HashMap<>();
                r.put("action", "deny");
                r.put("message", "Auto-denied (dontAsk mode): " + confirmMessage);
                return r;
            }
            Map<String, String> r = new HashMap<>();
            r.put("action", "confirm");
            r.put("message", confirmMessage);
            return r;
        }

        Map<String, String> r = new HashMap<>();
        r.put("action", "allow");
        return r;
    }

    /** 使用默认权限模式校验工具调用。 */
    public static Map<String, String> checkPermission(String toolName, Map<String, Object> inp) {
        return checkPermission(toolName, inp, PermissionMode.DEFAULT, null);
    }

    // ─── 结果截断 ─────────────────────────────────────────────────

    /** 单条 tool result 最大字符数。 */
    public static final int MAX_RESULT_CHARS = 50000;

    /** 截断过长的 tool result，保留首尾各一段。 */
    public static String truncateResult(String result) {
        if (result == null || result.length() <= MAX_RESULT_CHARS) {
            return result;
        }
        int keepEach = (MAX_RESULT_CHARS - 60) / 2;
        return result.substring(0, keepEach)
                + "\n\n[... truncated " + (result.length() - keepEach * 2) + " chars ...]\n\n"
                + result.substring(result.length() - keepEach);
    }

    // ─── 工具执行入口 ─────────────────────────────────────────────
    // agent / skill 由 Agent 处理，见 Agent.executeToolCall

    /**
     * 执行工具；{@code readFileState} 记录已读文件的 mtime，用于 read-before-edit 校验。
     */
    public static String executeTool(String name, Map<String, Object> inp,
                                     Map<String, Long> readFileState) {
        if (inp == null) {
            inp = new HashMap<>();
        }

        // ─── read-before-edit + mtime freshness checks ───────────
        if ("read_file".equals(name)) {
            String result = readFile(inp);
            if (readFileState != null && !result.startsWith("Error")) {
                try {
                    Path abs = Paths.get(str(inp, "file_path")).toAbsolutePath().normalize();
                    readFileState.put(abs.toString(), Files.getLastModifiedTime(abs).toMillis());
                } catch (IOException ignored) {
                }
            }
            return result;
        }

        if (("write_file".equals(name) || "edit_file".equals(name)) && readFileState != null) {
            try {
                Path abs = Paths.get(str(inp, "file_path")).toAbsolutePath().normalize();
                String absPath = abs.toString();
                if (Files.exists(abs)) {
                    if (!readFileState.containsKey(absPath)) {
                        String verb = "write_file".equals(name) ? "writing" : "editing";
                        return "Error: You must read this file before " + verb
                                + ". Use read_file first to see its current contents.";
                    }
                    long mtime = Files.getLastModifiedTime(abs).toMillis();
                    Long known = readFileState.get(absPath);
                    if (known == null || mtime != known) {
                        String verb = "write_file".equals(name) ? "writing" : "editing";
                        return "Warning: " + str(inp, "file_path")
                                + " was modified externally since your last read. Please read_file again before "
                                + verb + ".";
                    }
                }
            } catch (IOException ignored) {
            }
        }

        // tool_search: activate deferred tools and return their schemas
        if ("tool_search".equals(name)) {
            String query = strOr(inp, "query", "").toLowerCase(Locale.ROOT);
            List<Map<String, Object>> deferred = new ArrayList<>();
            for (Map<String, Object> t : TOOL_DEFINITIONS) {
                if (Boolean.TRUE.equals(t.get("deferred"))) {
                    deferred.add(t);
                }
            }
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Map<String, Object> t : deferred) {
                String tName = String.valueOf(t.get("name")).toLowerCase(Locale.ROOT);
                String desc = t.get("description") != null
                        ? String.valueOf(t.get("description")).toLowerCase(Locale.ROOT) : "";
                if (tName.contains(query) || desc.contains(query)) {
                    matches.add(t);
                }
            }
            if (matches.isEmpty()) {
                return "No matching deferred tools found.";
            }
            for (Map<String, Object> m : matches) {
                activatedTools.add(String.valueOf(m.get("name")));
            }
            JsonArray arr = new JsonArray();
            for (Map<String, Object> t : matches) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", String.valueOf(t.get("name")));
                obj.addProperty("description", t.get("description") != null
                        ? String.valueOf(t.get("description")) : "");
                obj.add("input_schema", GSON_COMPACT.toJsonTree(t.get("input_schema")));
                arr.add(obj);
            }
            return GSON.toJson(arr);
        }

        String result;
        switch (name) {
            case "write_file":
                result = writeFile(inp);
                break;
            case "edit_file":
                result = editFile(inp);
                break;
            case "list_files":
                result = listFiles(inp);
                break;
            case "grep_search":
                result = grepSearch(inp);
                break;
            case "run_shell":
                result = runShell(inp);
                break;
            case "web_fetch":
                result = webFetch(inp);
                break;
            case "web_search":
                result = webSearch(inp);
                break;
            default:
                return "Unknown tool: " + name;
        }

        // Update mtime after successful write/edit
        if (("write_file".equals(name) || "edit_file".equals(name))
                && readFileState != null && !result.startsWith("Error")) {
            try {
                Path abs = Paths.get(str(inp, "file_path")).toAbsolutePath().normalize();
                readFileState.put(abs.toString(), Files.getLastModifiedTime(abs).toMillis());
            } catch (IOException ignored) {
            }
        }

        return result;
    }

    /** 执行工具（不跟踪 readFileState）。 */
    public static String executeTool(String name, Map<String, Object> inp) {
        return executeTool(name, inp, null);
    }

    /** 将 JsonObject 工具入参转为 {@code Map<String, Object>}。 */
    public static Map<String, Object> jsonToMap(JsonObject obj) {
        Map<String, Object> map = new HashMap<>();
        if (obj == null) return map;
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            map.put(e.getKey(), jsonElementToObject(e.getValue()));
        }
        return map;
    }

    private static Object jsonElementToObject(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            if (el.getAsJsonPrimitive().isBoolean()) return el.getAsBoolean();
            if (el.getAsJsonPrimitive().isNumber()) return el.getAsNumber();
            return el.getAsString();
        }
        if (el.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : el.getAsJsonArray()) {
                list.add(jsonElementToObject(item));
            }
            return list;
        }
        if (el.isJsonObject()) {
            return jsonToMap(el.getAsJsonObject());
        }
        return el.toString();
    }
}
