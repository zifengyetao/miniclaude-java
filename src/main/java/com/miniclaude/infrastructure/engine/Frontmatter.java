package com.miniclaude.infrastructure.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YAML Frontmatter 解析与序列化工具。
 *
 * <p><b>职责</b>：解析与序列化 Markdown 文件顶部的 {@code ---} 分隔元数据块，
 * 将 {@code key: value} 行提取为 {@link Map}，剩余部分作为正文 body 返回。
 * 被 {@link Memory}、{@link Skills}、{@link Subagent} 等模块共享，用于读取
 * {@code .md} 配置文件中的元数据（name、description、type 等）。
 *
 * <p><b>在系统中的位置</b>：{@code infrastructure/engine} 层的基础工具类，
 * 不依赖 {@link Agent} 或 HTTP API，纯字符串/文件内容处理。
 *
 * <p><b>限制</b>：仅支持单行 {@code key: value} 格式，不支持嵌套 YAML、
 * 多行值或引号转义等复杂语法；与 Claude Code 的 frontmatter 约定一致。
 *
 * <p><b>线程安全</b>：所有方法均为无状态静态方法，可在多线程环境下安全调用。
 */
public final class Frontmatter {

    /** 私有构造，禁止实例化工具类。 */
    private Frontmatter() {}

    /**
     * Frontmatter 解析结果容器。
     *
     * <p>不可变值对象：构造后 {@link #meta} 与 {@link #body} 不再变更。
     */
    public static final class FrontmatterResult {
        /** frontmatter 键值对；构造时 null 会被替换为 {@link Collections#emptyMap()}。 */
        public final Map<String, String> meta;
        /** 去掉 frontmatter 块后的 Markdown 正文；null 会被替换为空串。 */
        public final String body;

        /**
         * 构造解析结果。
         *
         * @param meta frontmatter 键值对；可为 null（视为空 Map）
         * @param body 正文内容；可为 null（视为空串）
         */
        public FrontmatterResult(Map<String, String> meta, String body) {
            this.meta = meta != null ? meta : Collections.emptyMap();
            this.body = body != null ? body : "";
        }
    }

    /**
     * 从 Markdown 内容中解析 frontmatter 元数据与正文。
     *
     * <p>算法：
     * <ol>
     *   <li>首行必须为 {@code ---}，否则整篇视为正文、meta 为空</li>
     *   <li>自第二行起查找闭合 {@code ---}，未找到则同上</li>
     *   <li>两分隔符之间的行按第一个 {@code :} 拆分为 key/value</li>
     *   <li>闭合分隔符之后的内容 trim 后作为 body</li>
     * </ol>
     *
     * @param content 完整 Markdown 文本；null 时返回空 meta 与空 body
     * @return 元数据与正文的组合结果；永不返回 null
     * @sideeffects 无 I/O、无全局状态变更
     */
    public static FrontmatterResult parseFrontmatter(String content) {
        // ── 空输入快速返回 ──
        if (content == null) {
            return new FrontmatterResult(Collections.emptyMap(), "");
        }

        // split(..., -1) 保留末尾空行，避免丢失文件末尾换行语义
        String[] lines = content.split("\n", -1);

        // ── 无 frontmatter 开头标记：全文即正文 ──
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return new FrontmatterResult(Collections.emptyMap(), content);
        }

        // ── 扫描闭合 --- ──
        int endIdx = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                endIdx = i;
                break;
            }
        }
        // 只有开头 --- 没有结尾：视为格式不完整，整篇当正文
        if (endIdx == -1) {
            return new FrontmatterResult(Collections.emptyMap(), content);
        }

        // ── 解析 meta 行（LinkedHashMap 保持文件中的键顺序）──
        Map<String, String> meta = new LinkedHashMap<>();
        for (int i = 1; i < endIdx; i++) {
            int colonIdx = lines[i].indexOf(':');
            // 无冒号的行跳过（注释行或格式错误）
            if (colonIdx == -1) {
                continue;
            }
            String key = lines[i].substring(0, colonIdx).trim();
            String value = lines[i].substring(colonIdx + 1).trim();
            if (!key.isEmpty()) {
                meta.put(key, value);
            }
        }

        // ── 拼接 body（跳过 frontmatter 块与末尾空行 trim）──
        StringBuilder bodyBuilder = new StringBuilder();
        for (int i = endIdx + 1; i < lines.length; i++) {
            if (bodyBuilder.length() > 0) {
                bodyBuilder.append('\n');
            }
            bodyBuilder.append(lines[i]);
        }
        String body = bodyBuilder.toString().trim();
        return new FrontmatterResult(meta, body);
    }

    /**
     * 将元数据 Map 与正文序列化为带 frontmatter 的 Markdown 字符串。
     *
     * <p>输出格式：
     * <pre>
     * ---
     * key: value
     * ---
     *
     * body
     * </pre>
     *
     * @param meta 键值对元数据；null 时仅输出空 frontmatter 块
     * @param body 正文内容；null 时当作空串
     * @return 含 {@code ---} 分隔块的完整 Markdown；永不返回 null
     * @sideeffects 无
     */
    public static String formatFrontmatter(Map<String, String> meta, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        // ── 写入 meta 键值行 ──
        if (meta != null) {
            for (Map.Entry<String, String> entry : meta.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }
        sb.append("---\n");
        sb.append('\n'); // frontmatter 与正文之间保留空行（Markdown 惯例）
        sb.append(body != null ? body : "");
        return sb.toString();
    }
}
