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
 * 子 Agent 系统。
 *
 * <p>职责：提供内置子 Agent 类型（explore / plan / general）及
 * {@code .claude/agents/*.md} 自定义 Agent 的配置与 system prompt；
 * 按类型过滤可用工具集。
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层，
 * 由 {@link Agent} 的 {@code agent} 工具 fork 子实例时调用。
 * 对应 Claude Code 的 AgentTool fork-return 模式。
 */
public final class Subagent {

    /** explore/plan 子 Agent 允许的只读工具名集合。 */
    public static final Set<String> READ_ONLY_TOOLS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("read_file", "list_files", "grep_search")));

    /** explore 子 Agent 专用 system prompt（严格只读）。 */
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

    /** plan 子 Agent 专用 system prompt（只读 + 结构化计划）。 */
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

    /** general 子 Agent 专用 system prompt（全工具除 agent）。 */
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
     * 子 Agent 配置：system prompt + 允许的工具名白名单。
     * <ul>
     *   <li>{@code allowedToolNames == null} — 除 {@code "agent"} 外全部工具</li>
     *   <li>非 null — 仅 listed 工具（explore/plan 为只读集）</li>
     * </ul>
     */
    public static final class SubAgentConfig {
        public final String systemPrompt;
        /** Null 表示除 agent 外全部工具。 */
        public final List<String> allowedToolNames;

        public SubAgentConfig(String systemPrompt, List<String> allowedToolNames) {
            this.systemPrompt = systemPrompt;
            this.allowedToolNames = allowedToolNames;
        }
    }

    private static final class CustomAgentDef {
        final String name;
        final String description;
        final List<String> allowedTools;
        final String systemPrompt;

        CustomAgentDef(String name, String description, List<String> allowedTools, String systemPrompt) {
            this.name = name;
            this.description = description != null ? description : "";
            this.allowedTools = allowedTools;
            this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        }
    }

    private static volatile Map<String, CustomAgentDef> cachedCustomAgents = null;

    private Subagent() {}

    // ─── 自定义 Agent 发现（private）──────────────────────────────

    private static Map<String, CustomAgentDef> discoverCustomAgents() {
        if (cachedCustomAgents != null) {
            return cachedCustomAgents;
        }

        Map<String, CustomAgentDef> agents = new LinkedHashMap<>();
        // User-level (lower priority)
        loadAgentsFromDir(Paths.get(System.getProperty("user.home"), ".claude", "agents"), agents);
        // Project-level (higher priority, overwrites)
        loadAgentsFromDir(Paths.get("").toAbsolutePath().resolve(".claude").resolve("agents"), agents);

        cachedCustomAgents = agents;
        return agents;
    }

    private static void loadAgentsFromDir(Path directory, Map<String, CustomAgentDef> agents) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.md")) {
            for (Path entry : stream) {
                try {
                    String raw = new String(Files.readAllBytes(entry), StandardCharsets.UTF_8);
                    FrontmatterResult result = Frontmatter.parseFrontmatter(raw);
                    Map<String, String> meta = result.meta;

                    String name = meta.get("name");
                    if (name == null || name.isEmpty()) {
                        String fileName = entry.getFileName().toString();
                        int dot = fileName.lastIndexOf('.');
                        name = dot > 0 ? fileName.substring(0, dot) : fileName;
                    }

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

                    agents.put(name, new CustomAgentDef(
                            name,
                            meta.getOrDefault("description", ""),
                            allowedTools,
                            result.body));
                } catch (Exception ignored) {
                    // skip unreadable agent files
                }
            }
        } catch (Exception ignored) {
            // skip unreadable agent directories
        }
    }

    // ─── 配置查询 ─────────────────────────────────────────────────

    /**
     * 返回指定 agent 类型的 system prompt 与工具白名单。
     * explore/plan 使用 {@link #READ_ONLY_TOOLS}；general 与未限定工具的自定义 Agent 为 null。
     */
    public static SubAgentConfig getSubAgentConfig(String agentType) {
        CustomAgentDef custom = discoverCustomAgents().get(agentType);
        if (custom != null) {
            if (custom.allowedTools != null && !custom.allowedTools.isEmpty()) {
                return new SubAgentConfig(custom.systemPrompt, new ArrayList<>(custom.allowedTools));
            }
            // null = all tools except "agent"
            return new SubAgentConfig(custom.systemPrompt, null);
        }

        List<String> readOnly = new ArrayList<>(READ_ONLY_TOOLS);

        if ("explore".equals(agentType)) {
            return new SubAgentConfig(EXPLORE_PROMPT, readOnly);
        } else if ("plan".equals(agentType)) {
            return new SubAgentConfig(PLAN_PROMPT, readOnly);
        } else {
            // general — all except "agent"
            return new SubAgentConfig(GENERAL_PROMPT, null);
        }
    }

    // ─── 可用 Agent 类型列表（供 system prompt）──────────────────

    /** 返回所有可用 Agent 类型（内置 + 自定义）的名称与描述。 */
    public static List<Map<String, String>> getAvailableAgentTypes() {
        List<Map<String, String>> types = new ArrayList<>();
        types.add(agentTypeEntry("explore", "Fast, read-only codebase search and exploration"));
        types.add(agentTypeEntry("plan", "Read-only analysis with structured implementation plans"));
        types.add(agentTypeEntry("general", "Full tools for independent tasks"));

        for (Map.Entry<String, CustomAgentDef> e : discoverCustomAgents().entrySet()) {
            types.add(agentTypeEntry(e.getKey(), e.getValue().description));
        }
        return types;
    }

    private static Map<String, String> agentTypeEntry(String name, String description) {
        Map<String, String> m = new HashMap<>();
        m.put("name", name);
        m.put("description", description);
        return m;
    }

    /** 若有自定义 Agent，构建注入 system prompt 的描述段落。 */
    public static String buildAgentDescriptions() {
        List<Map<String, String>> types = getAvailableAgentTypes();
        if (types.size() <= 3) {
            return ""; // Only built-in types, already in system prompt
        }

        List<Map<String, String>> custom = types.subList(3, types.size());
        List<String> lines = new ArrayList<>();
        lines.add("\n# Custom Agent Types");
        lines.add("");
        for (Map<String, String> t : custom) {
            lines.add("- **" + t.get("name") + "**: " + t.get("description"));
        }
        return String.join("\n", lines);
    }

    /** 清除自定义 Agent 发现缓存。 */
    public static void resetAgentCache() {
        cachedCustomAgents = null;
    }
}
