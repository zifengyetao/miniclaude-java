package com.miniclaude.interfaces.rest.dto;

import java.time.Instant;

/**
 * 会话元数据 GET/POST 响应体 DTO。
 * <p>
 * 不含消息历史，仅暴露会话级元信息供列表与详情展示。
 */
public class SessionResponse {

    /** 会话 UUID 主键。 */
    private String id;
    /** 会话创建时间（UTC）。 */
    private Instant createdAt;
    /** 最近一次聊天或 touch 的活跃时间。 */
    private Instant lastActiveAt;
    /** 当前绑定的 LLM 模型 ID。 */
    private String model;
    /** 会话标题，通常由首条用户消息截断生成。 */
    private String title;

    public SessionResponse() {
    }

    public SessionResponse(String id, Instant createdAt, Instant lastActiveAt, String model, String title) {
        this.id = id;
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
        this.model = model;
        this.title = title;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
