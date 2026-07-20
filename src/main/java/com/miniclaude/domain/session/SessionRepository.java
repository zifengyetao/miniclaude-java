package com.miniclaude.domain.session;

import java.util.List;
import java.util.Optional;

/**
 * 会话持久化端口（Inbound Port for persistence）。
 * <p>
 * <b>为何放在 domain：</b>会话元数据是 Chat 聚合的持久化契约，领域层定义「存什么、怎么查」，
 * 不绑定 JDBC、JPA 或 Redis。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@link ChatSession#getId()} 在租户/全局范围内唯一（由应用层或适配器保证）。</li>
 *   <li>{@code save} 对同一 ID 应为 upsert 语义（实现约定）。</li>
 * </ul>
 * <p>
 * <b>边界：</b>
 * <ul>
 *   <li><b>application</b>：创建/列表/删除会话时调用。</li>
 *   <li><b>infrastructure</b>：Flyway 表 + JDBC/JPA 实现；对话上下文仍由 Agent 引擎按 sessionId 持有，不在此仓储。</li>
 * </ul>
 */
public interface SessionRepository {

    /**
     * 保存或更新会话元数据。
     *
     * @param session 待持久化的会话聚合
     * @return 持久化后的会话（可能含数据库生成字段）
     */
    ChatSession save(ChatSession session);

    /**
     * 按 ID 查询会话。
     *
     * @param id 会话 ID
     * @return 存在则返回，否则 empty
     */
    Optional<ChatSession> findById(String id);

    /**
     * 列出全部会话（通常按 lastActiveAt 降序，排序由适配器决定）。
     *
     * @return 会话列表，不为 null
     */
    List<ChatSession> findAll();

    /**
     * 删除指定会话；不存在时应 no-op 或抛错（实现约定）。
     * <p>
     * 应用层删除前应同步调用 {@link com.miniclaude.domain.agent.AgentGateway#closeSession} 释放引擎资源。
     *
     * @param id 会话 ID
     */
    void delete(String id);
}
