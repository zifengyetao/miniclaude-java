package com.miniclaude.domain.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 聊天会话聚合根（仅元数据，不含消息历史）。
 * <p>
 * <b>为何放在 domain：</b>会话 ID、标题、模型、活跃时间是 Chat 用例的核心概念；
 * 消息与工具状态由 Agent 引擎按 sessionId 持有， intentionally 不放入本聚合以免边界膨胀。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@code id} 创建后不可变；{@code createdAt} 不可变。</li>
 *   <li>{@code lastActiveAt} ≥ {@code createdAt}（由 {@link #touch} 维护）。</li>
 *   <li>可变字段仅 {@code lastActiveAt}、{@code model}、{@code title}，通过显式方法修改。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application 在发消息前 {@link #touch}；infrastructure {@link SessionRepository} 持久化。
 */
public final class ChatSession {

    /** 会话唯一标识（16 位 hex，由 {@link #create} 生成）。 */
    private final String id;
    /** 创建时间，创建后不可变。 */
    private final Instant createdAt;
    /** 最近一次活跃时间，每次对话后通过 {@link #touch} 更新。 */
    private Instant lastActiveAt;
    /** 当前绑定的模型，可在会话生命周期内通过 {@link #setModel} 变更。 */
    private String model;
    /** 会话标题，通常由首条消息摘要生成，通过 {@link #rename} 更新。 */
    private String title;

    /**
     * 全字段构造（供仓储重建）。
     *
     * @param id           会话 ID，不可 null
     * @param createdAt    创建时间，null 则用 now
     * @param lastActiveAt 最后活跃，null 则用 createdAt
     * @param model        模型标识
     * @param title        标题，可为 null
     */
    public ChatSession(String id, Instant createdAt, Instant lastActiveAt, String model, String title) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.lastActiveAt = lastActiveAt != null ? lastActiveAt : this.createdAt;
        this.model = model;
        this.title = title;
    }

    /**
     * 工厂方法：生成短 ID 并初始化时间戳。
     *
     * @param model 初始模型，可为 null
     * @return 新会话，status 等价于「刚创建、未命名」
     */
    public static ChatSession create(String model) {
        // 16 位 hex：足够工作台展示，且避免完整 UUID 过长
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Instant now = Instant.now();
        return new ChatSession(id, now, now, model, null);
    }

    /** @return 会话 ID */
    public String getId() {
        return id;
    }

    /** @return 创建时间 */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return 最后活跃时间 */
    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    /** @return 当前模型 */
    public String getModel() {
        return model;
    }

    /** @return 标题，可为 null */
    public String getTitle() {
        return title;
    }

    /**
     * 标记会话活跃，刷新 {@code lastActiveAt} 为当前时刻。
     * <p>状态转移：任意 → 活跃（时间戳更新）。
     */
    public void touch() {
        this.lastActiveAt = Instant.now();
    }

    /**
     * 更新会话展示标题。
     *
     * @param title 新标题，可为 null
     */
    public void rename(String title) {
        this.title = title;
    }

    /**
     * 切换会话使用的模型（不影响引擎内已有上下文，除非 application 同步 settings）。
     *
     * @param model 新模型标识
     */
    public void setModel(String model) {
        this.model = model;
    }
}
