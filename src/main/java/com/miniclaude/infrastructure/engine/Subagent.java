package com.miniclaude.infrastructure.engine;

import com.miniclaude.infrastructure.engine.Frontmatter.FrontmatterResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子 Agent（Sub-agent）配置与发现系统。
 *
 * <p><b>职责</b>
 * <ul>
 *   <li>提供三种内置子 Agent 类型（{@code explore} / {@code plan} / {@code general}）的
 *       system prompt 与工具白名单</li>
 *   <li>扫描 {@code ~/.claude/agents/*.md} 与 {@code .claude/agents/*.md}，
 *       解析 frontmatter 并注册自定义 Agent 类型</li>
 *   <li>为 {@link Agent} 的 {@code agent} 工具 fork 子实例时提供运行时配置</li>
 *   <li>生成可注入主 Agent system prompt 的自定义 Agent 描述段落</li>
 * </ul>
 *
 * <p><b>在系统中的位置</b>
 * 位于 {@code infrastructure/engine} 层，属于引擎基础设施，不直接依赖 Spring 或 REST。
 * 由 {@link Agent} 在调用 {@code agent} 工具时读取配置；对应 Claude Code 的 AgentTool
 * fork-return 模式（子实例独立上下文、独立工具集、结果回传父 Agent）。
 *
 * <p><b>线程安全</b>
 * <ul>
 *   <li>所有 public 常量（prompt 字符串、{@link #READ_ONLY_TOOLS}）在类加载时初始化，不可变，线程安全</li>
 *   <li>自定义 Agent 发现结果缓存在 {@link #cachedCustomAgents}（{@code volatile}）中；
 *       首次并发调用可能重复扫描磁盘，但最终写入同一引用，不会破坏数据一致性</li>
 *   <li>{@link #resetAgentCache()} 用于测试或热重载，调用后下次访问将重新扫描</li>
 * </ul>
 *
 * <p><b>限制与约定</b>
 * <ul>
 *   <li>自定义 Agent 文件解析失败时静默跳过，不抛出异常</li>
 *   <li>项目级 {@code .claude/agents/} 覆盖用户级同名 Agent（后加载优先）</li>
 *   <li>{@code allowed-tools} 未指定或为空时，子 Agent 可使用除 {@code agent} 外的全部工具
 *       （禁止递归 fork，避免无限嵌套）</li>
 *   <li>未知 {@code agentType} 字符串会 fallback 到 {@code general} 配置</li>
 *   <li>本类无 I/O 写操作，仅读取 Markdown 配置文件</li>
 * </ul>
 *
 * @see Agent
 * @see Frontmatter
 */
public final class Subagent {

    /**
     * explore / plan 子 Agent 允许的只读工具名集合（不可变）。
     * <p>仅包含 {@code read_file}、{@code list_files}、{@code grep_search}，
     * 从设计上禁止文件写入、Shell 执行等副作用工具。
     */
    public static final Set<String> READ_ONLY_TOOLS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("read_file", "list_files", "grep_search")));

    /**
     * explore 子 Agent 专用 system prompt（严格只读模式）。
     * <p>强调快速并行搜索、禁止任何文件修改，适用于代码库探索与定位。
     */
    public static final String EXPLORE_PROMPT =
            "You are a file search specialist for Mini Claude Code. You excel at thoroughly navigating and exploring codebases.\n"
            + "\n"
            + "=== CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===\n"
            + "This is a READ-ONLY exploration task. You are STRICTLY PROHIBITED from:\n"
            + "- Creating new files (no write_file, touch, or file creation of any kind)\n"
            + "- Modifying existing files (no edit_file operations)\n"
            + "- Deleting files (no rm or deletion)\n"
            + "- Running ANY commands that change system state\n"
            + "\n"
            + "Your role is EXCLUSIVELY to search and analyze existing code.\n"
            + "\n"
            + "Your strengths:\n"
            + "- Rapidly finding files using glob patterns\n"
            + "- Searching code and text with powerful regex patterns\n"
            + "- Reading and analyzing file contents\n"
            + "\n"
            + "Guidelines:\n"
            + "- Use list_files for broad file pattern matching\n"
            + "- Use grep_search for searching file contents with regex\n"
            + "- Use read_file when you know the specific file path you need to read\n"
            + "- Adapt your search approach based on the thoroughness level specified by the caller\n"
            + "\n"
            + "NOTE: You are meant to be a fast agent that returns output as quickly as possible. In order to achieve this you must:\n"
            + "- Make efficient use of the tools that you have at your disposal: be smart about how you search for files and implementations\n"
            + "- Wherever possible you should try to spawn multiple parallel tool calls for grepping and reading files\n"
            + "\n"
            + "Complete the user's search request efficiently and report your findings clearly.";

    /**
     * plan 子 Agent 专用 system prompt（只读 + 结构化计划输出）。
     * <p>在只读约束下分析架构并返回分步实施计划，不执行任何代码变更。
     */
    public static final String PLAN_PROMPT =
            "You are a Plan agent — a READ-ONLY sub-agent specialized for designing implementation plans.\n"
            + "\n"
            + "IMPORTANT CONSTRAINTS:\n"
            + "- You are READ-ONLY. You only have access to read_file, list_files, and grep_search.\n"
            + "- Do NOT attempt to modify any files.\n"
            + "\n"
            + "Your job:\n"
            + "- Analyze the codebase to understand the current architecture\n"
            + "- Design a step-by-step implementation plan\n"
            + "- Identify critical files that need modification\n"
            + "- Consider architectural trade-offs\n"
            + "\n"
            + "Return a structured plan with:\n"
            + "1. Summary of current state\n"
            + "2. Step-by-step implementation steps\n"
            + "3. Critical files for implementation\n"
            + "4. Potential risks or considerations";

    /**
     * general 子 Agent 专用 system prompt（全工具集，除 {@code agent} 外）。
     * <p>适用于需要读写、Shell 等完整能力的独立子任务，由父 Agent 委派执行。
     */
    public static final String GENERAL_PROMPT =
            "You are an agent for Mini Claude Code. Given the user's message, you should use the tools available to complete the task. Complete the task fully—don't gold-plate, but don't leave it half-done. When you complete the task, respond with a concise report covering what was done and any key findings — the caller will relay this to the user, so it only needs the essentials.\n"
            + "\n"
            + "Your strengths:\n"
            + "- Searching for code, configurations, and patterns across large codebases\n"
            + "- Analyzing multiple files to understand system architecture\n"
            + "- Investigating complex questions that require exploring many files\n"
            + "- Performing multi-step research tasks\n"
            + "\n"
            + "Guidelines:\n"
            + "- For file searches: search broadly when you don't know where something lives. Use read_file when you know the specific file path.\n"
            + "- For analysis: Start broad and narrow down. Use multiple search strategies if the first doesn't yield results.\n"
            + "- Be thorough: Check multiple locations, consider different naming conventions, look for related files.\n"
            + "- NEVER create files unless they're absolutely necessary for achieving your goal. ALWAYS prefer editing an existing file to creating a new one.";

    /**
     * 子 Agent 运行时配置快照：system prompt + 允许的工具名白名单。
     *
     * <p>工具白名单语义：
     * <ul>
     *   <li>{@code allowedToolNames == null} — 允许除 {@code "agent"} 外的全部工具</li>
     *   <li>非 null 列表 — 仅允许 listed 工具（explore/plan 为 {@link #READ_ONLY_TOOLS} 副本）</li>
     * </ul>
     *
     * <p>实例不可变（字段均为 {@code final}），可安全跨线程传递。
     */
    public static final class SubAgentConfig {
        /** 注入子 Agent 会话的 system prompt 全文。 */
        public final String systemPrompt;
        /**
         * 允许调用的工具名列表；{@code null} 表示除 {@code agent} 外全部工具。
         * <p>非 null 时为独立副本，修改返回值不影响内部缓存。
         */
        public final List<String> allowedToolNames;

        /**
         * 构造子 Agent 配置。
         *
         * @param systemPrompt    子 Agent system prompt，不可为 null（调用方保证）
         * @param allowedToolNames 工具白名单；{@code null} 表示无限制（除 agent）
         */
        public SubAgentConfig(String systemPrompt, List<String> allowedToolNames) {
            this.systemPrompt = systemPrompt;
            this.allowedToolNames = allowedToolNames;
        }
    }

    /**
     * 从磁盘 Markdown 解析出的自定义 Agent 定义（包内私有）。
     * <p>由 {@link #loadAgentsFromDir} 填充，经 {@link #discoverCustomAgents} 缓存。
     */
    private static final class CustomAgentDef {
        /** Agent 类型标识符，与 {@code agent} 工具的 {@code type} 参数对应。 */
        final String name;
        /** 人类可读描述，用于 system prompt 与 {@link #getAvailableAgentTypes()}。 */
        final String description;
        /**
         * frontmatter {@code allowed-tools} 解析结果；
         * {@code null} 表示未限制（除 agent 外全部工具）。
         */
        final List<String> allowedTools;
        /** Markdown body（frontmatter 之后的内容）作为 system prompt。 */
        final String systemPrompt;

        /**
         * @param name         Agent 名称
         * @param description  描述；{@code null} 时归一化为空串
         * @param allowedTools 工具白名单；可为 {@code null}
         * @param systemPrompt prompt 正文；{@code null} 时归一化为空串
         */
        CustomAgentDef(String name, String description, List<String> allowedTools, String systemPrompt) {
            this.name = name;
            this.description = description != null ? description : "";
            this.allowedTools = allowedTools;
            this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        }
    }

    /** 自定义 Agent 发现结果的全局缓存；{@code null} 表示尚未扫描或已被 {@link #resetAgentCache()} 清除。 */
    private static volatile Map<String, CustomAgentDef> cachedCustomAgents = null;

    /** 工具类，禁止实例化。 */
    private Subagent() {}

    // ─── 自定义 Agent 发现（private）──────────────────────────────

    /**
     * 扫描用户级与项目级 {@code .claude/agents/} 目录，合并并缓存自定义 Agent 定义。
     *
     * @return 不可变语义的 {@code name → CustomAgentDef} 映射（LinkedHashMap 保持加载顺序）
     * @sideeffects 首次调用时读取磁盘并写入 {@link #cachedCustomAgents}
     */
    private static Map<String, CustomAgentDef> discoverCustomAgents() {
        // ── 缓存命中：避免重复磁盘 I/O ──
        if (cachedCustomAgents != null) {
            return cachedCustomAgents;
        }

        Map<String, CustomAgentDef> agents = new LinkedHashMap<>();
        // User-level (lower priority) — 用户主目录下的全局 Agent 定义
        loadAgentsFromDir(Paths.get(System.getProperty("user.home"), ".claude", "agents"), agents);
        // Project-level (higher priority, overwrites) — 当前工作目录项目级定义，同名覆盖用户级
        loadAgentsFromDir(Paths.get("").toAbsolutePath().resolve(".claude").resolve("agents"), agents);

        cachedCustomAgents = agents;
        return agents;
    }

    /**
     * 从指定目录加载所有 {@code *.md} Agent 定义文件并合并到目标 Map。
     *
     * @param directory 待扫描目录（通常不存在，静默返回）
     * @param agents    输出容器；同名 Agent 会被后加载条目覆盖
     * @sideeffects 读取 {@code directory} 下 Markdown 文件；失败文件被跳过
     */
    private static void loadAgentsFromDir(Path directory, Map<String, CustomAgentDef> agents) {
        // ── 目录存在性检查 ──
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.md")) {
            // ── 遍历每个 Agent 定义文件 ──
            for (Path entry : stream) {
                try {
                    // 整文件 UTF-8 读取
                    String raw = new String(Files.readAllBytes(entry), StandardCharsets.UTF_8);
                    // 分离 YAML frontmatter 与 Markdown body
                    FrontmatterResult result = Frontmatter.parseFrontmatter(raw);
                    Map<String, String> meta = result.meta;

                    // ── Agent 名称：优先 frontmatter name，否则取文件名（去 .md 后缀）──
                    String name = meta.get("name");
                    if (name == null || name.isEmpty()) {
                        String fileName = entry.getFileName().toString();
                        int dot = fileName.lastIndexOf('.');
                        name = dot > 0 ? fileName.substring(0, dot) : fileName;
                    }

                    // ── 解析 allowed-tools：逗号分隔的工具名列表 ──
                    List<String> allowedTools = null;
                    if (meta.containsKey("allowed-tools")) {
                        allowedTools = new ArrayList<>();
                        for (String s : meta.get("allowed-tools").split(",")) {
                            String t = s.trim();
                            if (!t.isEmpty()) {
                                allowedTools.add(t);
                            }
                        }
                    }

                    // 写入/覆盖 agents Map
                    agents.put(name, new CustomAgentDef(
                            name,
                            meta.getOrDefault("description", ""),
                            allowedTools,
                            result.body));
                } catch (Exception ignored) {
                    // skip unreadable agent files — 单文件损坏不影响其他 Agent
                }
            }
        } catch (Exception ignored) {
            // skip unreadable agent directories — 目录不可读时整体跳过
        }
    }

    // ─── 配置查询 ─────────────────────────────────────────────────

    /**
     * 返回指定 agent 类型的 system prompt 与工具白名单。
     *
     * <p>解析顺序：自定义 Agent（磁盘）→ 内置 explore/plan/general。
     *
     * @param agentType Agent 类型字符串，如 {@code "explore"}、{@code "plan"}、
     *                  {@code "general"} 或自定义名称
     * @return 不可变的 {@link SubAgentConfig} 快照；explore/plan 使用
     *         {@link #READ_ONLY_TOOLS} 副本；general 与未限定工具的自定义 Agent 的
     *         {@code allowedToolNames} 为 {@code null}
     * @sideeffects 可能触发 {@link #discoverCustomAgents()} 的首次磁盘扫描
     */
    public static SubAgentConfig getSubAgentConfig(String agentType) {
        // ── 优先匹配自定义 Agent ──
        CustomAgentDef custom = discoverCustomAgents().get(agentType);
        if (custom != null) {
            if (custom.allowedTools != null && !custom.allowedTools.isEmpty()) {
                // 显式白名单：返回副本，防止调用方修改内部列表
                return new SubAgentConfig(custom.systemPrompt, new ArrayList<>(custom.allowedTools));
            }
            // null = all tools except "agent" — frontmatter 未指定或为空时的默认语义
            return new SubAgentConfig(custom.systemPrompt, null);
        }

        // ── 内置 Agent 类型分支 ──
        List<String> readOnly = new ArrayList<>(READ_ONLY_TOOLS);

        if ("explore".equals(agentType)) {
            return new SubAgentConfig(EXPLORE_PROMPT, readOnly);
        } else if ("plan".equals(agentType)) {
            return new SubAgentConfig(PLAN_PROMPT, readOnly);
        } else {
            // general — all except "agent"；未知 type 亦 fallback 至此
            return new SubAgentConfig(GENERAL_PROMPT, null);
        }
    }

    // ─── 可用 Agent 类型列表（供 system prompt）──────────────────

    /**
     * 返回所有可用 Agent 类型（内置 3 种 + 自定义）的名称与描述列表。
     *
     * @return 每项为 {@code Map}，含 {@code "name"} 与 {@code "description"} 键；
     *         顺序为 explore → plan → general → 自定义（LinkedHashMap 迭代序）
     * @sideeffects 可能触发自定义 Agent 发现
     */
    public static List<Map<String, String>> getAvailableAgentTypes() {
        List<Map<String, String>> types = new ArrayList<>();
        // 内置类型固定在前三位
        types.add(agentTypeEntry("explore", "Fast, read-only codebase search and exploration"));
        types.add(agentTypeEntry("plan", "Read-only analysis with structured implementation plans"));
        types.add(agentTypeEntry("general", "Full tools for independent tasks"));

        // 追加所有自定义 Agent
        for (Map.Entry<String, CustomAgentDef> e : discoverCustomAgents().entrySet()) {
            types.add(agentTypeEntry(e.getKey(), e.getValue().description));
        }
        return types;
    }

    /**
     * 构建单个 Agent 类型的 name/description 条目。
     *
     * @param name        Agent 标识符
     * @param description 人类可读描述
     * @return 含 {@code "name"}、{@code "description"} 的新 HashMap
     */
    private static Map<String, String> agentTypeEntry(String name, String description) {
        Map<String, String> m = new HashMap<>();
        m.put("name", name);
        m.put("description", description);
        return m;
    }

    /**
     * 若有自定义 Agent，构建可注入主 Agent system prompt 的 Markdown 描述段落。
     *
     * @return 自定义 Agent 列表的 Markdown 文本；仅内置 3 种时返回空串（内置描述已在别处）
     * @sideeffects 可能触发 {@link #getAvailableAgentTypes()} → 磁盘扫描
     */
    public static String buildAgentDescriptions() {
        List<Map<String, String>> types = getAvailableAgentTypes();
        // ── 无自定义 Agent：无需额外段落 ──
        if (types.size() <= 3) {
            return ""; // Only built-in types, already in system prompt
        }

        // 跳过前三项内置类型，仅格式化自定义部分
        List<Map<String, String>> custom = types.subList(3, types.size());
        List<String> lines = new ArrayList<>();
        lines.add("\n# Custom Agent Types");
        lines.add("");
        for (Map<String, String> t : custom) {
            lines.add("- **" + t.get("name") + "**: " + t.get("description"));
        }
        return String.join("\n", lines);
    }

    /**
     * 清除自定义 Agent 发现缓存。
     *
     * <p>下次调用 {@link #getSubAgentConfig}、{@link #getAvailableAgentTypes} 等时将重新扫描磁盘。
     *
     * @sideeffects 将 {@link #cachedCustomAgents} 置为 {@code null}
     */
    public static void resetAgentCache() {
        cachedCustomAgents = null;
    }
}
