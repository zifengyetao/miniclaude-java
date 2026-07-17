package com.miniclaude.domain.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 聊天会话聚合根。
 * <p>
 * 仅维护会话元数据（ID、时间、标题、模型）；对话上下文由 Agent 引擎按 sessionId 持有。
 */
public final class ChatSession {

    /** 会话唯一标识。 */
    private final String id;
    /** 创建时间，创建后不可变。 */
    private final Instant createdAt;
    /** 最近一次活跃时间，每次对话后更新。 */
    private Instant lastActiveAt;
    /** 当前绑定的模型，可在会话生命周期内变更。 */
    private String model;
    /** 会话标题，通常由首条消息摘要生成。 */
    private String title;

    public ChatSession(String id, Instant createdAt, Instant lastActiveAt, String model, String title) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.lastActiveAt = lastActiveAt != null ? lastActiveAt : this.createdAt;
        this.model = model;
        this.title = title;
    }

    /**
     * 工厂方法：生成短 ID 并初始化时间戳。
     */
    public static ChatSession create(String model) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Instant now = Instant.now();
        return new ChatSession(id, now, now, model, null);
    }

    public String getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public String getModel() {
        return model;
    }

    public String getTitle() {
        return title;
    }

    /** 标记会话活跃，刷新最后访问时间。 */
    public void touch() {
        this.lastActiveAt = Instant.now();
    }

    /** 更新会话展示标题。 */
    public void rename(String title) {
        this.title = title;
    }

    /** 切换会话使用的模型。 */
    public void setModel(String model) {
        this.model = model;
    }
}
