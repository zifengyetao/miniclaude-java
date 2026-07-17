package com.miniclaude.interfaces.rest.dto;

import java.time.Instant;

/**
 * 会话元数据响应体。
 */
public class SessionResponse {

    /** 会话唯一标识。 */
    private String id;
    /** 创建时间。 */
    private Instant createdAt;
    /** 最近活跃时间。 */
    private Instant lastActiveAt;
    /** 绑定的模型。 */
    private String model;
    /** 会话标题。 */
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
