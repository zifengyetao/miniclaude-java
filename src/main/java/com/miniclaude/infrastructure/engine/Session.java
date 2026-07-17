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
 * <p>职责：将会话元数据与对话历史以 JSON 文件形式保存到用户目录，
 * 支持加载、列举与查找最近会话，供 {@code --resume} 恢复对话。
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层，
 * 由 {@link Agent} 在每次对话后自动保存，由 {@link CliMain} 在启动时恢复。
 */
public final class Session {

    /** 会话文件存储目录：{@code ~/.mini-claude/sessions/} */
    public static final Path SESSION_DIR =
            Paths.get(System.getProperty("user.home"), ".mini-claude", "sessions");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private Session() {}

    private static void ensureDir() throws IOException {
        Files.createDirectories(SESSION_DIR);
    }

    /**
     * 将会话数据写入 JSON 文件。
     *
     * @param sessionId 会话唯一标识
     * @param data      含 metadata、anthropicMessages/openaiMessages 等字段
     */
    public static void saveSession(String sessionId, Map<String, Object> data) throws IOException {
        ensureDir();
        Path path = SESSION_DIR.resolve(sessionId + ".json");
        String json = GSON.toJson(data);
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按 sessionId 加载会话 JSON；文件不存在或解析失败时返回 {@code null}。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadSession(String sessionId) {
        Path path = SESSION_DIR.resolve(sessionId + ".json");
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return GSON.fromJson(text, MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 列举所有已保存会话的 metadata 列表（不含完整消息体）。
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
                    // skip corrupt session files
                }
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return results;
    }

    /**
     * 按 startTime 降序返回最近一次会话的 id；无会话时返回 {@code null}。
     */
    public static String getLatestSessionId() {
        List<Map<String, Object>> sessions = listSessions();
        if (sessions.isEmpty()) {
            return null;
        }
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
