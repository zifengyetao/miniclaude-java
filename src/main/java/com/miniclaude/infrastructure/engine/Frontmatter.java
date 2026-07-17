package com.miniclaude.infrastructure.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YAML Frontmatter 解析器。
 *
 * <p>职责：解析与序列化 Markdown 文件顶部的 {@code ---} 分隔元数据块，
 * 供 Memory、Skills、Subagent 等模块共享使用。
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层的基础工具类，
 * 不依赖 Agent 或 API，仅处理 {@code key: value} 格式的简单 YAML 键值对。
 */
public final class Frontmatter {

    private Frontmatter() {}

    /**
     * Frontmatter 解析结果，包含元数据 Map 与正文 body。
     */
    public static final class FrontmatterResult {
        public final Map<String, String> meta;
        public final String body;

        /** @param meta frontmatter 键值对；可为 null（视为空 Map） */
        public FrontmatterResult(Map<String, String> meta, String body) {
            this.meta = meta != null ? meta : Collections.emptyMap();
            this.body = body != null ? body : "";
        }
    }

    /**
     * 从 Markdown 内容中解析 frontmatter 元数据与正文。
     *
     * @param content 完整 Markdown 文本；若无 {@code ---} 开头则全部视为正文
     * @return 元数据与正文的组合结果
     */
    public static FrontmatterResult parseFrontmatter(String content) {
        if (content == null) {
            return new FrontmatterResult(Collections.emptyMap(), "");
        }

        String[] lines = content.split("\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return new FrontmatterResult(Collections.emptyMap(), content);
        }

        int endIdx = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                endIdx = i;
                break;
            }
        }
        if (endIdx == -1) {
            return new FrontmatterResult(Collections.emptyMap(), content);
        }

        Map<String, String> meta = new LinkedHashMap<>();
        for (int i = 1; i < endIdx; i++) {
            int colonIdx = lines[i].indexOf(':');
            if (colonIdx == -1) {
                continue;
            }
            String key = lines[i].substring(0, colonIdx).trim();
            String value = lines[i].substring(colonIdx + 1).trim();
            if (!key.isEmpty()) {
                meta.put(key, value);
            }
        }

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
     * @param meta 键值对元数据
     * @param body 正文内容
     * @return 含 {@code ---} 分隔块的完整 Markdown
     */
    public static String formatFrontmatter(Map<String, String> meta, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        if (meta != null) {
            for (Map.Entry<String, String> entry : meta.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }
        sb.append("---\n");
        sb.append('\n');
        sb.append(body != null ? body : "");
        return sb.toString();
    }
}
