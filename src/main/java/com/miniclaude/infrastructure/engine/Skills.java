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
 * Skills 技能系统。
 *
 * <p>职责：发现并解析 {@code .claude/skills/&lt;name&gt;/SKILL.md}，
 * 提供用户可调用（{@code /skill-name}）与 Agent 自动调用的 prompt 模板。
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层，
 * 由 {@link CliMain} REPL、{@link Agent} skill 工具及 {@link Prompt} 动态上下文引用。
 * 对应 Claude Code 的 skill 架构。
 */
public final class Skills {

    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    private static volatile List<SkillDefinition> cachedSkills = null;

    private Skills() {}

    /**
     * 已解析的技能定义（含 frontmatter 元数据与 prompt 模板）。
     */
    public static final class SkillDefinition {
        public final String name;
        public final String description;
        public final String whenToUse;
        public final List<String> allowedTools;
        public final boolean userInvocable;
        public final String context; // "inline" or "fork"
        public final String promptTemplate;
        public final String source; // "project" or "user"
        public final String skillDir;

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

    /** 扫描用户级与项目级 skills 目录并返回全部技能（带缓存）。 */
    public static List<SkillDefinition> discoverSkills() {
        if (cachedSkills != null) {
            return cachedSkills;
        }

        Map<String, SkillDefinition> skills = new LinkedHashMap<>();

        // User-level skills (lower priority)
        Path userDir = Paths.get(System.getProperty("user.home"), ".claude", "skills");
        loadSkillsFromDir(userDir, "user", skills);

        // Project-level skills (higher priority, overwrites)
        Path projectDir = Paths.get("").toAbsolutePath().resolve(".claude").resolve("skills");
        loadSkillsFromDir(projectDir, "project", skills);

        cachedSkills = new ArrayList<>(skills.values());
        return cachedSkills;
    }

    private static void loadSkillsFromDir(
            Path baseDir, String source, Map<String, SkillDefinition> skills) {
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                Path skillFile = entry.resolve("SKILL.md");
                if (!Files.exists(skillFile)) {
                    continue;
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

    private static SkillDefinition parseSkillFile(Path filePath, String source, String skillDir) {
        try {
            String raw = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            FrontmatterResult result = Frontmatter.parseFrontmatter(raw);
            Map<String, String> meta = result.meta;

            String name = meta.get("name");
            if (name == null || name.isEmpty()) {
                Path parent = filePath.getParent();
                name = parent != null ? parent.getFileName().toString() : "unknown";
            }

            boolean userInvocable = !"false".equals(meta.getOrDefault("user-invocable", "true"));
            String context = "fork".equals(meta.get("context")) ? "fork" : "inline";

            List<String> allowedTools = null;
            if (meta.containsKey("allowed-tools")) {
                String rawTools = meta.get("allowed-tools");
                if (rawTools.startsWith("[")) {
                    try {
                        allowedTools = GSON.fromJson(rawTools, STRING_LIST_TYPE);
                    } catch (Exception e) {
                        allowedTools = splitTools(rawTools.replaceAll("^\\[|\\]$", ""));
                    }
                } else {
                    allowedTools = splitTools(rawTools);
                }
            }

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
            return null;
        }
    }

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

    /** 按名称查找技能定义；未找到返回 {@code null}。 */
    public static SkillDefinition getSkillByName(String name) {
        for (SkillDefinition s : discoverSkills()) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** 将 {@code $ARGUMENTS} 等占位符替换为实际参数，解析技能 prompt。 */
    public static String resolveSkillPrompt(SkillDefinition skill, String args) {
        String prompt = skill.promptTemplate;
        String safeArgs = args != null ? args : "";
        prompt = prompt.replaceAll("\\$ARGUMENTS|\\$\\{ARGUMENTS\\}",
                java.util.regex.Matcher.quoteReplacement(safeArgs));
        prompt = prompt.replace("${CLAUDE_SKILL_DIR}", skill.skillDir);
        return prompt;
    }

    /**
     * 按名称执行技能，返回含 {@code prompt}、{@code allowed_tools}、{@code context} 的 Map；
     * 未找到时返回 {@code null}。
     */
    public static Map<String, Object> executeSkill(String skillName, String args) {
        SkillDefinition skill = getSkillByName(skillName);
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

    /** 构建注入 system prompt 的可用技能列表描述。 */
    public static String buildSkillDescriptions() {
        List<SkillDefinition> skills = discoverSkills();
        if (skills.isEmpty()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("# Available Skills");
        lines.add("");

        List<SkillDefinition> invocable = new ArrayList<>();
        List<SkillDefinition> autoOnly = new ArrayList<>();
        for (SkillDefinition s : skills) {
            if (s.userInvocable) {
                invocable.add(s);
            } else {
                autoOnly.add(s);
            }
        }

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

    /** 清除技能发现缓存（测试或热重载时使用）。 */
    public static void resetSkillCache() {
        cachedSkills = null;
    }
}
