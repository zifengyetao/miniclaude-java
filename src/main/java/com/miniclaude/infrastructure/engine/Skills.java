package com.miniclaude.infrastructure.engine;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.miniclaude.infrastructure.engine.Frontmatter.FrontmatterResult;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skills（技能）发现、解析与执行系统。
 *
 * <p><b>职责</b>
 * <ul>
 *   <li>扫描 {@code ~/.claude/skills/&lt;name&gt;/SKILL.md} 与
 *       {@code .claude/skills/&lt;name&gt;/SKILL.md}，解析 frontmatter 与 prompt 模板</li>
 *   <li>支持用户斜杠命令调用（{@code /skill-name}）与 Agent {@code skill} 工具自动调用</li>
 *   <li>解析 {@code $ARGUMENTS}、{@code ${CLAUDE_SKILL_DIR}} 等占位符</li>
 *   <li>生成可注入 system prompt 的可用技能描述段落</li>
 * </ul>
 *
 * <p><b>在系统中的位置</b>
 * 位于 {@code infrastructure/engine} 层。由 {@link CliMain} REPL 处理 {@code /} 命令、
 * {@link Agent} 的 {@code skill} 工具、以及 {@link Prompt} 动态上下文组装时引用。
 * 对应 Claude Code 的 skill 架构。
 *
 * <p><b>线程安全</b>
 * <ul>
 *   <li>{@link #cachedSkills} 为 {@code volatile} 引用；并发首次发现可能重复扫描，结果等价</li>
 *   <li>{@link SkillDefinition} 字段均为 {@code final}，实例不可变</li>
 *   <li>{@link #GSON} 为线程安全的静态实例（Gson 默认无共享可变状态）</li>
 * </ul>
 *
 * <p><b>限制与约定</b>
 * <ul>
 *   <li>每个技能目录必须包含 {@code SKILL.md}，否则被跳过</li>
 *   <li>项目级技能覆盖用户级同名技能</li>
 *   <li>{@code allowed-tools} 支持 JSON 数组或逗号分隔字符串</li>
 *   <li>{@code context} 仅识别 {@code inline}（默认）与 {@code fork}（独立子上下文执行）</li>
 *   <li>解析失败的技能文件返回 {@code null}，不影响其他技能加载</li>
 * </ul>
 *
 * @see Agent
 * @see Frontmatter
 * @see CliMain
 */
public final class Skills {

    /** Gson 实例，用于解析 frontmatter 中 JSON 格式的 {@code allowed-tools}。 */
    private static final Gson GSON = new Gson();
    /** {@code List<String>} 的 TypeToken，供 Gson 反序列化工具列表。 */
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    /** 技能发现结果的全局缓存；{@code null} 表示尚未扫描或已被 {@link #resetSkillCache()} 清除。 */
    private static volatile List<SkillDefinition> cachedSkills = null;

    /** 工具类，禁止实例化。 */
    private Skills() {}

    /**
     * 已解析的技能定义（含 frontmatter 元数据与 prompt 模板）。
     *
     * <p>由 {@link #discoverSkills()} 构建，字段均为 {@code final}，实例不可变，
     * 可安全在多线程间共享引用。
     */
    public static final class SkillDefinition {
        /** 技能唯一标识符，对应 {@code /name} 斜杠命令与 {@code skill} 工具的 {@code skill_name}。 */
        public final String name;
        /** 人类可读描述，用于 system prompt 技能列表。 */
        public final String description;
        /**
         * 建议使用场景说明（来自 {@code when_to_use} 或 {@code when-to-use} frontmatter）；
         * 可为 {@code null}。
         */
        public final String whenToUse;
        /**
         * 技能执行时允许的工具名列表；
         * {@code null} 表示不额外限制（由调用方 Agent 上下文决定）。
         */
        public final List<String> allowedTools;
        /** 是否允许用户通过 {@code /skill-name} 直接调用；{@code false} 时仅 Agent 自动调用。 */
        public final boolean userInvocable;
        /**
         * 执行上下文模式：{@code "inline"}（默认，在当前会话内注入 prompt）或
         * {@code "fork"}（fork 子 Agent 执行）。
         */
        public final String context; // "inline" or "fork"
        /** SKILL.md body（frontmatter 之后），可含 {@code $ARGUMENTS} 等占位符。 */
        public final String promptTemplate;
        /** 技能来源：{@code "user"}（用户主目录）或 {@code "project"}（当前项目）。 */
        public final String source; // "project" or "user"
        /** 技能目录绝对/相对路径字符串，供 {@code ${CLAUDE_SKILL_DIR}} 替换。 */
        public final String skillDir;

        /**
         * 构造不可变技能定义。
         *
         * @param name           技能名称
         * @param description    描述；{@code null} 归一化为空串
         * @param whenToUse      使用场景；可为 {@code null}
         * @param allowedTools   工具白名单；可为 {@code null}
         * @param userInvocable  是否用户可调用
         * @param context        上下文模式；{@code null} 默认为 {@code "inline"}
         * @param promptTemplate prompt 模板；{@code null} 归一化为空串
         * @param source         来源标签；{@code null} 默认为 {@code "project"}
         * @param skillDir       技能目录路径；{@code null} 归一化为空串
         */
        public SkillDefinition(
                String name,
                String description,
                String whenToUse,
                List<String> allowedTools,
                boolean userInvocable,
                String context,
                String promptTemplate,
                String source,
                String skillDir) {
            this.name = name;
            this.description = description != null ? description : "";
            this.whenToUse = whenToUse;
            this.allowedTools = allowedTools;
            this.userInvocable = userInvocable;
            this.context = context != null ? context : "inline";
            this.promptTemplate = promptTemplate != null ? promptTemplate : "";
            this.source = source != null ? source : "project";
            this.skillDir = skillDir != null ? skillDir : "";
        }
    }

    // ─── 技能发现 ───────────────────────────────────────────────

    /**
     * 扫描用户级与项目级 skills 目录并返回全部技能（带缓存）。
     *
     * @return 不可变语义的技能列表（ArrayList 副本）；项目级同名覆盖用户级
     * @sideeffects 首次调用时读取磁盘并写入 {@link #cachedSkills}
     */
    public static List<SkillDefinition> discoverSkills() {
        // ── 缓存命中 ──
        if (cachedSkills != null) {
            return cachedSkills;
        }

        // LinkedHashMap 保证迭代顺序且支持同名覆盖
        Map<String, SkillDefinition> skills = new LinkedHashMap<>();

        // User-level skills (lower priority) — ~/.claude/skills/
        Path userDir = Paths.get(System.getProperty("user.home"), ".claude", "skills");
        loadSkillsFromDir(userDir, "user", skills);

        // Project-level skills (higher priority, overwrites) — 当前项目 .claude/skills/
        Path projectDir = Paths.get("").toAbsolutePath().resolve(".claude").resolve("skills");
        loadSkillsFromDir(projectDir, "project", skills);

        cachedSkills = new ArrayList<>(skills.values());
        return cachedSkills;
    }

    /**
     * 从指定基目录扫描子目录，加载各 {@code SKILL.md} 并合并到 skills Map。
     *
     * @param baseDir  skills 根目录（如 {@code .claude/skills}）
     * @param source   来源标签 {@code "user"} 或 {@code "project"}
     * @param skills   输出容器；同名技能会被覆盖
     * @sideeffects 读取 {@code baseDir} 下各子目录的 SKILL.md
     */
    private static void loadSkillsFromDir(
            Path baseDir, String source, Map<String, SkillDefinition> skills) {
        // ── 目录存在性检查 ──
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            // ── 每个子目录代表一个技能 ──
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue; // 跳过非目录条目
                }
                Path skillFile = entry.resolve("SKILL.md");
                if (!Files.exists(skillFile)) {
                    continue; // 无 SKILL.md 的目录不是有效技能
                }
                SkillDefinition skill = parseSkillFile(skillFile, source, entry.toString());
                if (skill != null) {
                    skills.put(skill.name, skill);
                }
            }
        } catch (Exception ignored) {
            // skip unreadable skill directories
        }
    }

    /**
     * 解析单个 SKILL.md 文件为 {@link SkillDefinition}。
     *
     * @param filePath  SKILL.md 路径
     * @param source    来源标签
     * @param skillDir  技能目录路径字符串
     * @return 解析成功返回定义；任何异常返回 {@code null}
     * @sideeffects 读取 {@code filePath} 文件内容
     */
    private static SkillDefinition parseSkillFile(Path filePath, String source, String skillDir) {
        try {
            String raw = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            FrontmatterResult result = Frontmatter.parseFrontmatter(raw);
            Map<String, String> meta = result.meta;

            // ── 技能名称：frontmatter name 优先，否则取父目录名 ──
            String name = meta.get("name");
            if (name == null || name.isEmpty()) {
                Path parent = filePath.getParent();
                name = parent != null ? parent.getFileName().toString() : "unknown";
            }

            // ── user-invocable：默认 true，仅显式 "false" 时禁用斜杠命令 ──
            boolean userInvocable = !"false".equals(meta.getOrDefault("user-invocable", "true"));
            // ── context：仅 "fork" 启用 fork 模式，其余均为 inline ──
            String context = "fork".equals(meta.get("context")) ? "fork" : "inline";

            // ── allowed-tools：支持 JSON 数组或逗号分隔 ──
            List<String> allowedTools = null;
            if (meta.containsKey("allowed-tools")) {
                String rawTools = meta.get("allowed-tools");
                if (rawTools.startsWith("[")) {
                    // JSON 数组格式，如 ["read_file","grep_search"]
                    try {
                        allowedTools = GSON.fromJson(rawTools, STRING_LIST_TYPE);
                    } catch (Exception e) {
                        // JSON 解析失败时降级为去括号后的逗号分割
                        allowedTools = splitTools(rawTools.replaceAll("^\\[|\\]$", ""));
                    }
                } else {
                    // 逗号分隔格式，如 read_file, grep_search
                    allowedTools = splitTools(rawTools);
                }
            }

            // ── when_to_use：兼容 snake_case 与 kebab-case 两种 frontmatter 键名 ──
            String whenToUse = meta.get("when_to_use");
            if (whenToUse == null || whenToUse.isEmpty()) {
                whenToUse = meta.get("when-to-use");
            }

            return new SkillDefinition(
                    name,
                    meta.getOrDefault("description", ""),
                    whenToUse,
                    allowedTools,
                    userInvocable,
                    context,
                    result.body,
                    source,
                    skillDir);
        } catch (Exception e) {
            return null; // 单文件解析失败不影响整体发现
        }
    }

    /**
     * 将逗号分隔的工具名字符串拆分为去空白后的列表。
     *
     * @param raw 原始字符串，如 {@code " read_file , grep_search "}
     * @return 非空工具名列表（可能为空列表，不会为 null）
     */
    private static List<String> splitTools(String raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // ─── 技能解析与执行 ─────────────────────────────────────────

    /**
     * 按名称查找技能定义。
     *
     * @param name 技能名称，与 {@link SkillDefinition#name} 精确匹配
     * @return 找到的定义；未找到返回 {@code null}
     * @sideeffects 可能触发 {@link #discoverSkills()}
     */
    public static SkillDefinition getSkillByName(String name) {
        for (SkillDefinition s : discoverSkills()) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 将技能 prompt 模板中的占位符替换为实际参数。
     *
     * <p>支持的占位符：
     * <ul>
     *   <li>{@code $ARGUMENTS} 或 {@code ${ARGUMENTS}} — 用户/Agent 传入的参数文本</li>
     *   <li>{@code ${CLAUDE_SKILL_DIR}} — 技能目录路径</li>
     * </ul>
     *
     * @param skill 技能定义，不可为 {@code null}
     * @param args  调用参数；{@code null} 视为空串
     * @return 替换后的 prompt 字符串
     * @sideeffects 无 I/O；{@code quoteReplacement} 防止参数中的 {@code $} 被正则误解释
     */
    public static String resolveSkillPrompt(SkillDefinition skill, String args) {
        String prompt = skill.promptTemplate;
        String safeArgs = args != null ? args : "";
        // 替换 $ARGUMENTS / ${ARGUMENTS}，quoteReplacement 避免用户输入破坏 replaceAll
        prompt = prompt.replaceAll("\\$ARGUMENTS|\\$\\{ARGUMENTS\\}",
                java.util.regex.Matcher.quoteReplacement(safeArgs));
        prompt = prompt.replace("${CLAUDE_SKILL_DIR}", skill.skillDir);
        return prompt;
    }

    /**
     * 按名称执行技能，返回供 Agent 使用的结构化结果。
     *
     * @param skillName 技能名称
     * @param args      可选参数字符串，传入 {@link #resolveSkillPrompt}
     * @return 含 {@code "prompt"}、{@code "allowed_tools"}、{@code "context"} 的 Map；
     *         技能不存在时返回 {@code null}
     * @sideeffects 可能触发技能发现；不修改磁盘或 Agent 状态
     */
    public static Map<String, Object> executeSkill(String skillName, String args) {
        SkillDefinition skill = getSkillByName(skillName);
        // ── 技能不存在 ──
        if (skill == null) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("prompt", resolveSkillPrompt(skill, args));
        result.put("allowed_tools", skill.allowedTools);
        result.put("context", skill.context);
        return result;
    }

    // ─── 系统提示词片段 ─────────────────────────────────────────

    /**
     * 构建注入 system prompt 的可用技能列表 Markdown 描述。
     *
     * <p>分两组展示：用户可斜杠调用的技能、仅 Agent 自动调用的技能。
     *
     * @return Markdown 文本；无技能时返回空串
     * @sideeffects 可能触发 {@link #discoverSkills()}
     */
    public static String buildSkillDescriptions() {
        List<SkillDefinition> skills = discoverSkills();
        // ── 无技能：无需注入段落 ──
        if (skills.isEmpty()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("# Available Skills");
        lines.add("");

        // ── 按 userInvocable 分组 ──
        List<SkillDefinition> invocable = new ArrayList<>();
        List<SkillDefinition> autoOnly = new ArrayList<>();
        for (SkillDefinition s : skills) {
            if (s.userInvocable) {
                invocable.add(s);
            } else {
                autoOnly.add(s);
            }
        }

        // ── 用户可调用技能（斜杠命令）──
        if (!invocable.isEmpty()) {
            lines.add("User-invocable skills (user types /<name> to invoke):");
            for (SkillDefinition s : invocable) {
                lines.add("- **/" + s.name + "**: " + s.description);
                if (s.whenToUse != null && !s.whenToUse.isEmpty()) {
                    lines.add("  When to use: " + s.whenToUse);
                }
            }
            lines.add("");
        }

        // ── 仅 Agent 自动调用的技能 ──
        if (!autoOnly.isEmpty()) {
            lines.add("Auto-invocable skills (use the skill tool when appropriate):");
            for (SkillDefinition s : autoOnly) {
                lines.add("- **" + s.name + "**: " + s.description);
                if (s.whenToUse != null && !s.whenToUse.isEmpty()) {
                    lines.add("  When to use: " + s.whenToUse);
                }
            }
            lines.add("");
        }

        lines.add("To invoke a skill programmatically, use the `skill` tool with the skill name and optional arguments.");
        return String.join("\n", lines);
    }

    /**
     * 清除技能发现缓存。
     *
     * <p>供单元测试或开发时热重载 SKILL.md 后强制重新扫描。
     *
     * @sideeffects 将 {@link #cachedSkills} 置为 {@code null}
     */
    public static void resetSkillCache() {
        cachedSkills = null;
    }
}
