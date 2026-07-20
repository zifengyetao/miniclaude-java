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
 * Agent 工具注册表与运行时执行器。
 *
 * <p>本类是 {@code infrastructure/engine} 层的核心组件，对应 Python 版 {@code tools.py}，
 * 在每一轮 LLM tool call 时被 {@link Agent} 调用。职责包括：
 * <ul>
 *   <li>构建并暴露全部工具的 JSON Schema 定义（{@link #TOOL_DEFINITIONS}）</li>
 *   <li>按 deferred 激活策略过滤当前可用工具（{@link #getActiveToolDefinitions()}）</li>
 *   <li>执行具体工具实现（读/写/编辑/搜索/shell/网络等）</li>
 *   <li>多层权限校验（模式、规则文件、危险命令、plan 模式约束）</li>
 *   <li>read-before-edit 与 mtime 新鲜度保护，防止未读或过期编辑</li>
 * </ul>
 *
 * <p><b>循环依赖规避：</b>{@code agent} 与 {@code skill} 工具的 schema 在此注册，
 * 但实际执行由 {@link Agent} 完成，避免 {@code Tools} ↔ {@code Agent} 编译期环依赖。
 *
 * <p><b>线程安全：</b>工具定义与权限规则缓存为静态不可变/懒加载；
 * {@link #activatedTools} 使用 {@link Collections#synchronizedSet} 保护 deferred 激活状态。
 *
 * <p><b>副作用边界：</b>文件/ shell / 网络工具可能修改磁盘或发起外部请求；
 * 调用方应始终先经 {@link #checkPermission} 再 {@link #executeTool}。
 *
 * @see Agent
 * @see #checkPermission(String, Map, String, String)
 * @see #executeTool(String, Map, Map)
 */
public final class Tools {

    /** 禁止实例化；全部为静态 API。 */
    private Tools() {}

    // ─── 权限模式常量 ───────────────────────────────────────────

    /**
     * Agent 运行时权限模式字符串常量集合。
     *
     * <p>由 {@link Agent} 传入 {@link #checkPermission} 的 {@code mode} 参数使用，
     * 控制只读/编辑/确认/绕过等行为。值为协议字符串，勿与枚举混淆。
     */
    public static final class PermissionMode {
        /** 默认模式：只读工具自动放行；编辑/shell/危险操作按规则与确认策略处理。 */
        public static final String DEFAULT = "default";
        /** 计划模式：仅允许读工具 + 向 plan 文件写入；禁止 shell 与非 plan 路径编辑。 */
        public static final String PLAN = "plan";
        /** 自动接受编辑：{@link #EDIT_TOOLS} 内工具无需逐次确认。 */
        public static final String ACCEPT_EDITS = "acceptEdits";
        /** 绕过全部权限检查（测试/受控环境慎用）。 */
        public static final String BYPASS_PERMISSIONS = "bypassPermissions";
        /** 不询问用户：需确认的操作直接拒绝而非弹出 confirm。 */
        public static final String DONT_ASK = "dontAsk";
        /** 自动模式别名（与 DEFAULT 等价，由上层 Agent 解析）。 */
        public static final String AUTO = "auto";

        private PermissionMode() {}
    }

    /**
     * 只读类工具名集合（不可变）。
     *
     * <p>成员：{@code read_file}、{@code list_files}、{@code grep_search}、
     * {@code web_fetch}、{@code web_search}。
     * 在 {@link PermissionMode#DEFAULT} 及 plan 模式下通常直接 {@code allow}。
     */
    public static final Set<String> READ_TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "read_file", "list_files", "grep_search", "web_fetch", "web_search"
    )));

    /**
     * 文件编辑类工具名集合（不可变）。
     *
     * <p>成员：{@code write_file}、{@code edit_file}。
     * plan 模式下仅当目标路径等于 {@code planFilePath} 时允许。
     */
    public static final Set<String> EDIT_TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "write_file", "edit_file"
    )));

    /**
     * 可安全并行执行的工具名集合（不可变）。
     *
     * <p>与 {@link #READ_TOOLS} 成员相同：只读、无本地写副作用，
     * Agent 层可据此决定是否并发 dispatch。
     */
    public static final Set<String> CONCURRENCY_SAFE_TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "read_file", "list_files", "grep_search", "web_fetch", "web_search"
    )));

    /** 当前 JVM 是否运行在 Windows（影响 shell 与 grep 实现路径）。 */
    private static final boolean IS_WIN = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    /** 带 pretty-print 的 Gson，用于 {@code tool_search} 返回可读 JSON。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 紧凑 Gson，用于嵌套 schema 序列化（无多余空白）。 */
    private static final Gson GSON_COMPACT = new Gson();

    // ─── 工具 schema 定义 ─────────────────────────────────────────

    /**
     * 全部工具定义的不可变快照；类加载时由 {@link #buildToolDefinitions()} 构建。
     *
     * <p>含 deferred 标记的工具默认不会出现在 {@link #getActiveToolDefinitions()} 中，
     * 需经 {@code tool_search} 激活后才暴露给 LLM。
     */
    public static final List<Map<String, Object>> TOOL_DEFINITIONS = buildToolDefinitions();

    /**
     * 构建全部 Agent 工具的 JSON Schema 定义列表。
     *
     * <p>每个定义包含 {@code name}、{@code description}、{@code input_schema}，
     * 可选 {@code deferred}。工具分组：
     * <ol>
     *   <li>文件 I/O：read / write / edit / list / grep</li>
     *   <li>系统：run_shell</li>
     *   <li>扩展：skill（执行在 Agent）、web_search / web_fetch</li>
     *   <li>模式切换：enter_plan_mode / exit_plan_mode（deferred）</li>
     *   <li>编排：agent（子 Agent，执行在 Agent）、tool_search（deferred 激活器）</li>
     * </ol>
     *
     * @return 不可变的工具定义列表
     * @sideeffects 无；纯内存构建，仅在类初始化时调用一次
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buildToolDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>();

        // ── 文件读取：带行号格式化输出 ──
        defs.add(tool("read_file",
                "Read the contents of a file. Returns the file content with line numbers.",
                schema(props(
                        prop("file_path", "string", "The path to the file to read")
                ), "file_path"),
                false));

        // ── 文件写入：覆盖或创建，返回行数预览 ──
        defs.add(tool("write_file",
                "Write content to a file. Creates the file if it doesn't exist, overwrites if it does.",
                schema(props(
                        prop("file_path", "string", "The path to the file to write"),
                        prop("content", "string", "The content to write to the file")
                ), "file_path", "content"),
                false));

        // ── 精确字符串替换编辑（old_string 须唯一匹配）──
        defs.add(tool("edit_file",
                "Edit a file by replacing an exact string match with new content. The old_string must match exactly (including whitespace and indentation).",
                schema(props(
                        prop("file_path", "string", "The path to the file to edit"),
                        prop("old_string", "string", "The exact string to find and replace"),
                        prop("new_string", "string", "The string to replace it with")
                ), "file_path", "old_string", "new_string"),
                false));

        // ── 目录 glob 列举（默认跳过 node_modules 与隐藏路径）──
        defs.add(tool("list_files",
                "List files matching a glob pattern. Returns matching file paths.",
                schema(props(
                        prop("pattern", "string", "Glob pattern to match files (e.g., \"**/*.ts\", \"src/**/*\")"),
                        prop("path", "string", "Base directory to search from. Defaults to current directory.")
                ), "pattern"),
                false));

        // ── 正则搜索：非 Windows 优先系统 grep，失败则 Java 回退 ──
        defs.add(tool("grep_search",
                "Search for a pattern in files. Returns matching lines with file paths and line numbers.",
                schema(props(
                        prop("pattern", "string", "The regex pattern to search for"),
                        prop("path", "string", "Directory or file to search in. Defaults to current directory."),
                        prop("include", "string", "File glob pattern to include (e.g., \"*.ts\", \"*.py\")")
                ), "pattern"),
                false));

        // ── Shell 执行：含可选超时（默认 30s）──
        Map<String, Object> timeoutProp = prop("timeout", "number", "Timeout in milliseconds (default: 30000)");
        defs.add(tool("run_shell",
                "Execute a shell command and return its output. Use this for running tests, installing packages, git operations, etc.",
                schema(props(
                        prop("command", "string", "The shell command to execute"),
                        timeoutProp
                ), "command"),
                false));

        // ── Skill 调用：schema 在此，执行由 Agent 加载 .claude/skills/ ──
        defs.add(tool("skill",
                "Invoke a registered skill by name. Skills are prompt templates loaded from .claude/skills/. Returns the skill's resolved prompt to follow.",
                schema(props(
                        prop("skill_name", "string", "The name of the skill to invoke"),
                        prop("args", "string", "Optional arguments to pass to the skill")
                ), "skill_name"),
                false));

        // ── 网络：搜索（DuckDuckGo HTML）与抓取（HTTP GET）──
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

        // ── Plan 模式切换（deferred：需 tool_search 或默认激活策略暴露）──
        defs.add(tool("enter_plan_mode",
                "Enter plan mode to switch to a read-only planning phase. In plan mode, you can only read files and write to the plan file.",
                schema(props(), new String[0]),
                true));

        defs.add(tool("exit_plan_mode",
                "Exit plan mode after you have finished writing your plan to the plan file.",
                schema(props(), new String[0]),
                true));

        // ── 子 Agent 启动：type 枚举 explore / plan / general ──
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

        // ── Deferred 工具发现与激活入口 ──
        defs.add(tool("tool_search",
                "Search for available tools by name or keyword. Returns full schema definitions for matching deferred tools so you can use them.",
                schema(props(
                        prop("query", "string", "Tool name or search keywords")
                ), "query"),
                false));

        // 冻结列表，防止运行时篡改 schema
        return Collections.unmodifiableList(defs);
    }

    /**
     * 组装单个工具定义 Map。
     *
     * @param name         工具名（LLM function name）
     * @param description  给模型的自然语言说明
     * @param inputSchema  JSON Schema 对象（type/properties/required）
     * @param deferred     若为 true，初始不暴露，需 tool_search 激活
     * @return 含 name/description/input_schema（及可选 deferred）的 Map
     * @sideeffects 无
     */
    private static Map<String, Object> tool(String name, String description,
                                            Map<String, Object> inputSchema, boolean deferred) {
        Map<String, Object> t = new HashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("input_schema", inputSchema);
        // deferred 工具在 getActiveToolDefinitions 中默认过滤
        if (deferred) {
            t.put("deferred", true);
        }
        return t;
    }

    /**
     * 合并多个 {@link #prop} 返回的单键 Map 为 properties 对象。
     *
     * @param entries 各 prop(...) 结果
     * @return 合并后的 properties Map
     * @sideeffects 无
     */
    @SafeVarargs
    private static Map<String, Object> props(Map<String, Object>... entries) {
        Map<String, Object> m = new HashMap<>();
        for (Map<String, Object> e : entries) {
            m.putAll(e);
        }
        return m;
    }

    /**
     * 定义单个 JSON Schema 字段（type + description）。
     *
     * @param name        参数名
     * @param type        JSON Schema type 字符串
     * @param description 字段说明
     * @return 单键 wrapper，便于 props 合并
     * @sideeffects 无
     */
    private static Map<String, Object> prop(String name, String type, String description) {
        Map<String, Object> field = new HashMap<>();
        field.put("type", type);
        field.put("description", description);
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put(name, field);
        return wrapper;
    }

    /**
     * 构建 object 类型 input_schema。
     *
     * @param properties 字段定义 Map
     * @param required   必填字段名列表
     * @return 含 type/properties/required 的 schema Map
     * @sideeffects 无
     */
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

    /** 已通过 tool_search 激活的 deferred 工具名；线程安全 Set。 */
    private static final Set<String> activatedTools = Collections.synchronizedSet(new HashSet<>());

    /**
     * 清空 deferred 激活状态。
     *
     * @sideeffects 修改 {@link #activatedTools}；测试隔离用
     */
    public static void resetActivatedTools() {
        activatedTools.clear();
    }

    /**
     * 返回当前应发送给 LLM API 的工具定义子集。
     *
     * <p>过滤规则：{@code deferred != true} 或已在 {@link #activatedTools} 中；
     * 输出副本移除 {@code deferred} 键，避免泄露内部标记。
     *
     * @param allTools 完整定义列表；{@code null} 时使用 {@link #TOOL_DEFINITIONS}
     * @return 活跃工具定义的浅拷贝列表（不含 deferred 键）
     * @sideeffects 无
     */
    public static List<Map<String, Object>> getActiveToolDefinitions(List<Map<String, Object>> allTools) {
        List<Map<String, Object>> tools = allTools != null ? allTools : TOOL_DEFINITIONS;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            boolean deferred = Boolean.TRUE.equals(t.get("deferred"));
            String name = String.valueOf(t.get("name"));
            // 非 deferred 或已激活才纳入 API  payload
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

    /** @see #getActiveToolDefinitions(List) */
    public static List<Map<String, Object>> getActiveToolDefinitions() {
        return getActiveToolDefinitions(null);
    }

    /**
     * 列出尚未激活的 deferred 工具名。
     *
     * @param allTools 完整定义；{@code null} 时使用 {@link #TOOL_DEFINITIONS}
     * @return deferred 且未在 {@link #activatedTools} 中的工具名
     * @sideeffects 无
     */
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

    /** @see #getDeferredToolNames(List) */
    public static List<String> getDeferredToolNames() {
        return getDeferredToolNames(null);
    }

    // ─── 各工具实现（package-private）──────────────────────────────

    /** 从工具入参 Map 取字符串；缺失则 null。 */
    private static String str(Map<String, Object> inp, String key) {
        Object v = inp.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 取字符串，null 或空串时返回默认值。 */
    private static String strOr(Map<String, Object> inp, String key, String def) {
        String v = str(inp, key);
        return v == null || v.isEmpty() ? def : v;
    }

    /**
     * 解析数值参数：支持 Number 或可解析 String。
     *
     * @param def 无法解析时的默认值
     */
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

    /**
     * 读取文件并以「行号 | 内容」格式返回。
     *
     * @param inp 须含 {@code file_path}
     * @return 带行号的文本；失败时以 {@code Error reading file:} 前缀返回
     * @sideeffects 仅读磁盘，不修改文件
     */
    static String readFile(Map<String, Object> inp) {
        try {
            Path path = Paths.get(str(inp, "file_path"));
            byte[] bytes = Files.readAllBytes(path);
            String content = new String(bytes, StandardCharsets.UTF_8);
            // UTF-8 解码遇非法序列时 Java 默认替换字符，等价 Python errors="replace"
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

    /**
     * 写入文件（覆盖或创建），并维护 memory 索引。
     *
     * @param inp 须含 {@code file_path}、{@code content}
     * @return 成功摘要与前 30 行预览
     * @sideeffects 写磁盘；可能触发 {@link #autoUpdateMemoryIndex}
     */
    static String writeFile(Map<String, Object> inp) {
        try {
            Path path = Paths.get(str(inp, "file_path"));
            // 确保父目录存在
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

    /**
     * 解析项目 memory 目录路径（与 Memory 模块逻辑对齐，避免编译期硬依赖）。
     *
     * @return {@code ~/.mini-claude/projects/<hash>/memory}
     * @throws RuntimeException SHA-256 或目录创建失败时包装抛出
     * @sideeffects 可能创建 memory 目录
     */
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

    /**
     * 当写入 memory 目录下的 .md 文件时，自动重建 MEMORY.md 索引。
     *
     * @param filePath 刚写入文件的绝对或相对路径
     * @sideeffects 可能读写 {@code memory/MEMORY.md}；失败静默忽略
     */
    private static void autoUpdateMemoryIndex(String filePath) {
        try {
            String memDir = getMemoryDir().toString();
            // 仅处理 memory 目录内、非 MEMORY.md 的 markdown
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
                            // 需同时有 name 与 type frontmatter 才列入索引
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

    /** 智能单引号 Unicode 范围匹配器。 */
    private static final Pattern SMART_SINGLE = Pattern.compile("[\u2018\u2019\u2032]");
    /** 智能双引号 Unicode 范围匹配器。 */
    private static final Pattern SMART_DOUBLE = Pattern.compile("[\u201c\u201d\u2033]");

    /**
     * 将智能引号规范为 ASCII {@code '} / {@code "}。
     *
     * @param s 原始字符串
     * @return 规范化后的字符串
     * @sideeffects 无
     */
    static String normalizeQuotes(String s) {
        s = SMART_SINGLE.matcher(s).replaceAll("'");
        s = SMART_DOUBLE.matcher(s).replaceAll("\"");
        return s;
    }

    /**
     * 在文件内容中定位 {@code searchString}，支持引号容错匹配。
     *
     * @param fileContent  完整文件文本
     * @param searchString LLM 提供的 old_string
     * @return 文件中实际匹配的子串；找不到返回 null
     * @sideeffects 无
     */
    static String findActualString(String fileContent, String searchString) {
        // 优先精确匹配
        if (fileContent.contains(searchString)) {
            return searchString;
        }
        // 引号规范化后再 indexOf，取原文件对应片段
        String normSearch = normalizeQuotes(searchString);
        String normFile = normalizeQuotes(fileContent);
        int idx = normFile.indexOf(normSearch);
        if (idx != -1) {
            return fileContent.substring(idx, idx + searchString.length());
        }
        return null;
    }

    /**
     * 生成 unified-diff 风格片段，供 edit_file 成功反馈展示。
     *
     * @param oldContent 编辑前全文
     * @param oldString  实际被替换片段
     * @param newString  新片段
     * @return @@ 头 + -/+ 行
     * @sideeffects 无
     */
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

    /** 统计字符串中换行符个数。 */
    private static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    /** 统计 needle 在 haystack 中非重叠出现次数。 */
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

    /**
     * 按唯一 old_string 精确替换编辑文件。
     *
     * @param inp file_path / old_string / new_string
     * @return 成功含 diff；失败含 Error 前缀
     * @sideeffects 写磁盘
     */
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
            // 多处匹配拒绝，避免误改
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

    /**
     * 按 glob 模式列举文件（递归 walk，上限 200 条）。
     *
     * @param inp {@code pattern} 必填；{@code path} 可选基目录
     * @return 相对路径列表或错误信息
     * @sideeffects 只读磁盘
     */
    static String listFiles(Map<String, Object> inp) {
        try {
            Path base = Paths.get(strOr(inp, "path", ".")).toAbsolutePath().normalize();
            String pattern = str(inp, "pattern");
            if (pattern == null) {
                return "Error listing files: pattern is required";
            }

            List<String> files = new ArrayList<>();
            int[] extra = {0};

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
                    // 剪枝 node_modules 与隐藏目录
                    if ("node_modules".equals(name) || name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path rel = base.relativize(file);
                    // 跳过路径中含隐藏段或 node_modules 的文件
                    for (Path part : rel) {
                        String p = part.toString();
                        if ("node_modules".equals(p) || p.startsWith(".")) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    boolean match = matcher.matches(rel) || matcher.matches(file.getFileName());
                    // Windows：用正斜杠路径再试一次 glob
                    if (!match) {
                        String relStr = rel.toString().replace('\\', '/');
                        PathMatcher m2 = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
                        match = m2.matches(Paths.get(relStr));
                    }
                    if (match && Files.isRegularFile(file)) {
                        String display = base.equals(Paths.get(".").toAbsolutePath().normalize())
                                ? file.toString()
                                : rel.toString();
                        Path cwd = Paths.get(".").toAbsolutePath().normalize();
                        if (!base.equals(cwd) || str(inp, "path") != null) {
                            display = rel.toString().replace('\\', '/');
                        } else {
                            display = rel.toString().replace('\\', '/');
                            if (display.isEmpty()) {
                                display = file.getFileName().toString();
                            }
                        }
                        // 硬上限 200，超出计入 extra
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

    /**
     * 在目录或文件中搜索正则模式。
     *
     * <p>非 Windows 优先调用系统 {@code grep -r}（10s 超时）；
     * 失败或 Windows 则 {@link #grepJava} 纯 Java 回退。
     *
     * @param inp pattern / path / include
     * @return 匹配行（最多 100 条）或提示信息
     * @sideeffects 非 Windows 可能 spawn 子进程
     */
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
                    // grep 非 0/1 退出码：降级 Java 实现
                }
            } catch (Exception ignored) {
            }
        }

        return grepJava(pattern, path, include);
    }

    /**
     * 读取子进程 stdout/stderr 为 UTF-8 字符串。
     *
     * @param in 进程流
     * @return 全文（保留换行）
     * @throws IOException 读流失败
     * @sideeffects 关闭输入流（try-with-resources）
     */
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

    /**
     * 简易 glob 匹配（{@code *} / {@code ?}），用于 grep include 过滤。
     *
     * @param name    文件名
     * @param pattern glob 模式
     * @return 是否匹配
     * @sideeffects 无
     */
    private static boolean fnmatch(String name, String pattern) {
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

    /**
     * 纯 Java 递归 grep 回退实现。
     *
     * @param pattern   正则字符串
     * @param directory 根路径
     * @param include   文件名 glob 过滤，可 null
     * @return 匹配行或错误/空结果提示
     * @sideeffects 只读磁盘
     */
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

    /**
     * 递归遍历目录执行 grep；单文件路径则直接 grep 该文件。
     *
     * @param d              当前路径
     * @param regex          编译后的模式
     * @param includePattern 文件名 glob
     * @param matches        输出列表（最多 100 条）
     * @param extra          超出计数器（长度 1 数组）
     * @sideeffects 填充 matches / extra
     */
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

    /**
     * 对单个文件逐行正则匹配。
     *
     * @sideeffects 可能向 matches 追加；超 100 条时递增 extra[0]
     */
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

    /**
     * 执行 shell 命令并捕获 stdout/stderr。
     *
     * @param inp command 必填；timeout 毫秒默认 30000
     * @return 成功为 stdout；失败含 exit code 与 stderr
     * @sideeffects spawn 子进程，可能修改系统状态（取决于 command）
     */
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

    /**
     * HTTP GET 抓取 URL 正文；HTML 会剥离标签。
     *
     * @param inp url 必填；max_length 默认 50000
     * @return 文本内容或 HTTP/网络错误信息
     * @sideeffects 发起出站 HTTP 请求
     */
    static String webFetch(Map<String, Object> inp) {
        String url = strOr(inp, "url", "");
        int maxLength = num(inp, "max_length", 50000).intValue();

        // 仅允许 http(s)  scheme
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
     * 通过 DuckDuckGo HTML 页面搜索（无需 API Key）。
     *
     * <p>返回标题/URL/摘要列表，供模型后续 {@link #webFetch} 深入阅读。
     *
     * @param inp query 必填；max_results 1–10，默认 5
     * @return 编号结果列表或错误/空结果提示
     * @sideeffects 发起出站 HTTP 请求
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

    /**
     * 从 DuckDuckGo HTML 响应解析搜索结果块。
     *
     * @param html       原始 HTML
     * @param maxResults 最多返回条数
     * @return 格式化条目（标题 + URL + 可选 snippet）
     * @sideeffects 无
     */
    private static List<String> parseDuckDuckGoResults(String html, int maxResults) {
        List<String> out = new ArrayList<>();
        Pattern linkPat = Pattern.compile(
                "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
                Pattern.CASE_INSENSITIVE);
        Pattern snippetPat = Pattern.compile(
                "class=\"result__snippet\"[^>]*>([\\s\\S]*?)</(?:a|td|span)>",
                Pattern.CASE_INSENSITIVE);

        // 第一遍：收集链接与标题
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
                break;
            }
        }

        // 第二遍：收集 snippet 位置
        Matcher snipM = snippetPat.matcher(html);
        List<int[]> snipPos = new ArrayList<>();
        List<String> snips = new ArrayList<>();
        while (snipM.find()) {
            snipPos.add(new int[]{snipM.start(), snipM.end()});
            snips.add(stripHtml(snipM.group(1)).trim());
        }

        // 将 snippet 关联到相邻 result 链接
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

    /**
     * 解码 DuckDuckGo 跳转链接（{@code uddg=} 参数）为真实 URL。
     *
     * @param href 原始 href
     * @return 解码后的 http(s) URL；失败则返回原 href
     * @sideeffects 无
     */
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

    /**
     * 剥离 HTML：去 script/style/标签，合并空白。
     *
     * @param text 原始 HTML 或混合文本
     * @return 可读纯文本
     * @sideeffects 无
     */
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

    /**
     * 解码常见 HTML 实体为字符。
     *
     * @param s 含实体的字符串
     * @return 解码后字符串；null 视为空串
     * @sideeffects 无
     */
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

    /**
     * 危险 shell 命令正则列表（命中则需用户 confirm）。
     *
     * <p>覆盖 rm、破坏性 git、sudo、磁盘/进程/系统控制及 Windows 等价命令。
     */
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

    /**
     * 判断 shell 命令是否匹配 {@link #DANGEROUS_PATTERNS} 中任一模式。
     *
     * @param command 待检命令字符串
     * @return 命中危险模式为 true
     * @sideeffects 无
     */
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

    /** 懒加载缓存的用户+项目 allow/deny 规则；{@link #resetPermissionCache} 可清空。 */
    private static Map<String, List<Map<String, String>>> cachedRules = null;

    /**
     * 解析单条权限规则字符串。
     *
     * <p>格式：{@code tool(pattern)} 或仅 {@code tool}（无 pattern 表示匹配该工具全部调用）。
     *
     * @param rule 原始规则，如 {@code read_file(/secret/*)}
     * @return 含 {@code tool}、{@code pattern}（可 null）的 Map
     * @sideeffects 无
     */
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

    /**
     * 读取单个 settings.json 并解析为 JsonObject。
     *
     * @param filePath 配置文件路径
     * @return JSON 对象；不存在或解析失败返回 null
     * @sideeffects 读磁盘
     */
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

    /**
     * 合并加载用户级与项目级 permissions.allow / permissions.deny 规则。
     *
     * <p>路径：{@code ~/.claude/settings.json} 与 {@code .claude/settings.json}。
     * 结果缓存在 {@link #cachedRules}。
     *
     * @return Map 键 {@code allow}、{@code deny}，值为规则 Map 列表
     * @sideeffects 首次调用读磁盘并填充 {@link #cachedRules}
     */
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

    /**
     * 清除权限规则缓存，使下次 {@link #loadPermissionRules()} 重新读文件。
     *
     * @sideeffects 将 {@link #cachedRules} 置 null
     */
    public static void resetPermissionCache() {
        cachedRules = null;
    }

    /**
     * 判断单条 allow/deny 规则是否匹配当前工具调用。
     *
     * @param rule     含 tool/pattern 的规则 Map
     * @param toolName 工具名
     * @param inp      工具入参（用于提取 command 或 file_path）
     * @return 工具名一致且 pattern 匹配（或无 pattern）时为 true
     * @sideeffects 无
     */
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

        // 后缀 * 表示前缀匹配
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return value.equals(pattern);
    }

    /**
     * 按 deny 优先、allow 次之评估 settings 规则。
     *
     * @param toolName 工具名
     * @param inp      工具入参
     * @return {@code deny}、{@code allow} 或 null（无规则命中）
     * @sideeffects 可能触发 {@link #loadPermissionRules()} 读盘
     */
    private static String checkPermissionRules(String toolName, Map<String, Object> inp) {
        Map<String, List<Map<String, String>>> rules = loadPermissionRules();
        // deny 列表优先：任一命中即拒绝
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
     * 综合校验工具调用是否允许执行。
     *
     * <p>决策流水线（按顺序短路）：
     * <ol>
     *   <li>settings.json deny 规则 → {@code action=deny}</li>
     *   <li>{@link PermissionMode#PLAN}：限制编辑目标与 shell</li>
     *   <li>{@link PermissionMode#BYPASS_PERMISSIONS} → allow</li>
     *   <li>settings allow 规则 → allow</li>
     *   <li>{@link #READ_TOOLS} → allow</li>
     *   <li>plan 模式切换工具 → allow</li>
     *   <li>{@link PermissionMode#ACCEPT_EDITS} + {@link #EDIT_TOOLS} → allow</li>
     *   <li>危险 shell / 新建文件写 / 编辑不存在文件 → confirm（或 dontAsk 时 deny）</li>
     *   <li>默认 allow</li>
     * </ol>
     *
     * @param toolName     工具名
     * @param inp          工具入参 Map
     * @param mode         {@link PermissionMode} 字符串；null 视为 DEFAULT
     * @param planFilePath plan 模式下唯一可写文件路径；非 plan 模式可 null
     * @return 含 {@code action}（allow/deny/confirm）及可选 {@code message} 的 Map
     * @sideeffects 可能读 settings.json（经缓存）
     */
    public static Map<String, String> checkPermission(
            String toolName,
            Map<String, Object> inp,
            String mode,
            String planFilePath) {

        if (mode == null) {
            mode = PermissionMode.DEFAULT;
        }

        // ── 第 1 层：显式 deny 规则（最高优先级）──
        String ruleResult = checkPermissionRules(toolName, inp);
        if ("deny".equals(ruleResult)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "deny");
            r.put("message", "Denied by permission rule for " + toolName);
            return r;
        }

        // ── 第 2 层：plan 模式约束 ──
        if (PermissionMode.PLAN.equals(mode)) {
            if (EDIT_TOOLS.contains(toolName)) {
                String filePath = str(inp, "file_path");
                if (filePath == null) {
                    filePath = str(inp, "path");
                }
                // 仅允许写入 plan 文件本身
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

        // ── 第 3 层：全局绕过 ──
        if (PermissionMode.BYPASS_PERMISSIONS.equals(mode)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "allow");
            return r;
        }

        // ── 第 4 层：显式 allow 规则 ──
        if ("allow".equals(ruleResult)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "allow");
            return r;
        }

        // ── 第 5 层：只读工具白名单 ──
        if (READ_TOOLS.contains(toolName)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "allow");
            return r;
        }

        // plan 模式进出工具始终允许（由 Agent 处理状态切换）
        if ("enter_plan_mode".equals(toolName) || "exit_plan_mode".equals(toolName)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "allow");
            return r;
        }

        // ── 第 6 层：自动接受编辑模式 ──
        if (PermissionMode.ACCEPT_EDITS.equals(mode) && EDIT_TOOLS.contains(toolName)) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "allow");
            return r;
        }

        // ── 第 7 层：需用户确认的高风险操作 ──
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

        // ── 默认放行 ──
        Map<String, String> r = new HashMap<>();
        r.put("action", "allow");
        return r;
    }

    /** 使用 {@link PermissionMode#DEFAULT} 且无 plan 文件的简化校验。 */
    public static Map<String, String> checkPermission(String toolName, Map<String, Object> inp) {
        return checkPermission(toolName, inp, PermissionMode.DEFAULT, null);
    }

    // ─── 结果截断 ─────────────────────────────────────────────────

    /** 单条 tool result 回传 LLM 的最大字符数（与 web_fetch 默认 max_length 对齐）。 */
    public static final int MAX_RESULT_CHARS = 50000;

    /**
     * 截断过长 tool 结果，保留首尾各一段以便模型仍见上下文。
     *
     * @param result 原始结果；null 安全
     * @return 未超长则原样；否则中间替换为 truncated 标记
     * @sideeffects 无
     */
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
     * 工具执行总入口（除 agent/skill 外均由本方法 dispatch）。
     *
     * <p>执行前/后逻辑：
     * <ul>
     *   <li>{@code read_file}：成功后更新 {@code readFileState} 中该路径 mtime</li>
     *   <li>{@code write_file}/{@code edit_file}：校验必须先 read 且 mtime 未变（read-before-edit）</li>
     *   <li>{@code tool_search}：激活匹配 deferred 工具并返回 schema JSON</li>
     *   <li>其它：switch 分发至 package-private 实现</li>
     * </ul>
     *
     * @param name          工具名
     * @param inp           入参 Map；null 时当作空 Map
     * @param readFileState 可选；路径 → 上次 read 时的 mtime 毫秒。Agent 跨 turn 维护
     * @return 工具结果字符串（错误也以字符串返回，非抛异常）
     * @sideeffects 视工具而定：读写文件、网络、shell、更新 readFileState / activatedTools
     */
    public static String executeTool(String name, Map<String, Object> inp,
                                     Map<String, Long> readFileState) {
        if (inp == null) {
            inp = new HashMap<>();
        }

        // ─── 分支 A：read_file — 记录 mtime 供后续编辑校验 ───
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

        // ─── 分支 B：write/edit — read-before-edit + mtime 新鲜度 ───
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

        // ─── 分支 C：tool_search — deferred 工具发现与激活 ───
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
            // 激活匹配项，后续 getActiveToolDefinitions 会暴露
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

        // ─── 分支 D：标准工具 switch 分发 ───
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

        // ─── 分支 E：写/编成功后刷新 readFileState mtime ───
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

    /** @see #executeTool(String, Map, Map) */
    public static String executeTool(String name, Map<String, Object> inp) {
        return executeTool(name, inp, null);
    }

    /**
     * 将 LLM 返回的 JsonObject 工具参数转为 {@code Map<String, Object>}。
     *
     * @param obj JSON 对象；null 返回空 Map
     * @return 递归转换后的 Map
     * @sideeffects 无
     */
    public static Map<String, Object> jsonToMap(JsonObject obj) {
        Map<String, Object> map = new HashMap<>();
        if (obj == null) return map;
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            map.put(e.getKey(), jsonElementToObject(e.getValue()));
        }
        return map;
    }

    /**
     * 递归将 {@link JsonElement} 转为 Java 对象（Map/List/基本类型）。
     *
     * @param el Gson 元素
     * @return Map、List、Boolean、Number、String 或 null
     * @sideeffects 无
     */
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
