package com.miniclaude.domain.session;

import java.util.List;
import java.util.Optional;

/**
 * 会话持久化端口。
 * <p>
 * 定义会话元数据的存取契约，具体存储由基础设施层实现。
 */
public interface SessionRepository {

    /** 保存或更新会话。 */
    ChatSession save(ChatSession session);

    /** 按 ID 查询会话。 */
    Optional<ChatSession> findById(String id);

    /** 列出全部会话。 */
    List<ChatSession> findAll();

    /** 删除指定会话。 */
    void delete(String id);
}
