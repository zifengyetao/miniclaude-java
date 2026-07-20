package com.miniclaude.infrastructure.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 会话持久化管理。
 *
 * <p><b>职责</b>：将 Agent 对话会话以 JSON 文件形式保存到用户主目录，
 * 支持按 sessionId 加载、列举 metadata、查找最近一次会话，
 * 供 CLI {@code --resume} 恢复完整对话历史。
 *
 * <p><b>存储布局</b>：{@code ~/.mini-claude/sessions/{sessionId}.json}，
 * 每个文件含 {@code metadata}（id、startTime 等）及
 * {@code anthropicMessages} / {@code openaiMessages} 消息数组。
 *
 * <p><b>在系统中的位置</b>：{@code infrastructure/engine} 层，
 * 由 {@link Agent#autoSave()} 在每轮对话后写入，
 * 由 {@link CliMain#main} 在 {@code --resume} 时读取并 {@link Agent#restoreSession}。
 *
 * <p><b>容错</b>：损坏或无法解析的 JSON 文件在列举时被跳过，不抛异常。
 */
public final class Session {

    /** 会话文件存储根目录：{@code ~/.mini-claude/sessions/}。 */
    public static final Path SESSION_DIR =
            Paths.get(System.getProperty("user.home"), ".mini-claude", "sessions");

    /** Gson 实例，pretty-print 便于人工调试会话文件。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 反序列化顶层 JSON 对象用的 TypeToken 类型。 */
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    /** 私有构造，禁止实例化。 */
    private Session() {}

    /**
     * 确保会话目录存在。
     *
     * @throws IOException 创建目录失败时抛出
     * @sideeffects 可能在磁盘上创建 {@link #SESSION_DIR}
     */
    private static void ensureDir() throws IOException {
        Files.createDirectories(SESSION_DIR);
    }

    /**
     * 将会话数据写入 JSON 文件（覆盖同名 sessionId）。
     *
     * @param sessionId 会话唯一标识，用作文件名 {@code {sessionId}.json}
     * @param data      含 metadata、anthropicMessages/openaiMessages 等字段的 Map
     * @throws IOException 目录创建或文件写入失败
     * @sideeffects 写入/覆盖 {@link #SESSION_DIR} 下的 JSON 文件
     */
    public static void saveSession(String sessionId, Map<String, Object> data) throws IOException {
        ensureDir();
        Path path = SESSION_DIR.resolve(sessionId + ".json");
        String json = GSON.toJson(data);
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按 sessionId 加载会话 JSON。
     *
     * @param sessionId 会话标识
     * @return 解析后的 Map；文件不存在或 JSON 无效时返回 {@code null}
     * @sideeffects 只读磁盘，无写入
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadSession(String sessionId) {
        Path path = SESSION_DIR.resolve(sessionId + ".json");
        // ── 文件不存在 ──
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return GSON.fromJson(text, MAP_TYPE);
        } catch (Exception e) {
            // 损坏 JSON：静默返回 null，由调用方提示用户
            return null;
        }
    }

    /**
     * 列举所有已保存会话的 metadata 列表（不含完整消息体）。
     *
     * <p>仅提取每个 JSON 文件中的 {@code metadata} 字段，用于 REPL 展示或
     * {@link #getLatestSessionId()} 排序。
     *
     * @return metadata Map 列表；目录为空或不可读时返回空列表（非 null）
     * @sideeffects 只读扫描 {@link #SESSION_DIR}
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listSessions() {
        try {
            ensureDir();
        } catch (IOException e) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        try (Stream<Path> stream = Files.list(SESSION_DIR)) {
            // ── 仅处理 .json 后缀的会话文件 ──
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .collect(Collectors.toList());
            for (Path f : files) {
                try {
                    String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                    Map<String, Object> data = GSON.fromJson(text, MAP_TYPE);
                    if (data != null && data.containsKey("metadata")) {
                        Object meta = data.get("metadata");
                        if (meta instanceof Map) {
                            results.add((Map<String, Object>) meta);
                        }
                    }
                } catch (Exception ignored) {
                    // 跳过损坏的会话文件，不影响其他文件
                }
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return results;
    }

    /**
     * 按 startTime 降序返回最近一次会话的 id。
     *
     * <p>用于 {@code --resume} 默认恢复目标：取 metadata.startTime 最新的一条。
     *
     * @return 最近会话的 id 字符串；无任何会话时返回 {@code null}
     * @sideeffects 调用 {@link #listSessions()}，只读
     */
    public static String getLatestSessionId() {
        List<Map<String, Object>> sessions = listSessions();
        if (sessions.isEmpty()) {
            return null;
        }
        // ── 按 startTime 字符串降序（ISO-8601 格式可字典序比较）──
        sessions.sort(Comparator.comparing(
                (Map<String, Object> s) -> {
                    Object v = s.get("startTime");
                    return v != null ? v.toString() : "";
                },
                Comparator.reverseOrder()));
        Object id = sessions.get(0).get("id");
        return id != null ? id.toString() : null;
    }
}
