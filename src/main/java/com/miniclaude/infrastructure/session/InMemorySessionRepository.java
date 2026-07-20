package com.miniclaude.infrastructure.session;

import com.miniclaude.domain.session.ChatSession;
import com.miniclaude.domain.session.SessionRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat 会话的进程内内存仓储实现（{@link SessionRepository}）。
 *
 * <p><b>适用场景</b>：开发、单机演示、集成测试。生产多实例部署应替换为 JDBC/Redis 实现，
 * 否则各 Pod 会话列表不一致且重启丢数据。</p>
 *
 * <p><b>与 {@link com.miniclaude.infrastructure.runtime.LegacyAgentRuntime} 的关系</b>：
 * 本仓储保存 Chat 元数据（标题、最后活跃时间等）；引擎会话状态由
 * {@code LegacyAgentRuntime} 内 {@code ConcurrentHashMap} 单独管理——两者通过
 * {@code sessionId} 关联但存储分离。</p>
 *
 * <p><b>并发语义</b>：{@link ConcurrentHashMap} 保证单次 put/get/remove 原子；
 * {@link #save} 的「读-改-写」若在上层非原子，仍可能丢更新——当前 Chat 应用层按
 * sessionId 串行更新，满足原型需求。</p>
 */
@Repository
public class InMemorySessionRepository implements SessionRepository {

    /** 线程安全的会话存储；键为 sessionId，值为完整 {@link ChatSession} 快照 */
    private final ConcurrentHashMap<String, ChatSession> store = new ConcurrentHashMap<>();

    /**
     * 保存或覆盖会话快照。
     *
     * <p>同一 id 重复 save 为覆盖语义（最后写入生效），不保留历史版本。</p>
     *
     * @param session 非 null 会话实体
     * @return 传入的 session（便于链式调用）
     */
    @Override
    public ChatSession save(ChatSession session) {
        store.put(session.getId(), session);
        return session;
    }

    /**
     * 按 id 查找会话。
     *
     * @param id 会话标识
     * @return 存在则 {@code Optional.of}，否则 {@code Optional.empty()}（不抛异常）
     */
    @Override
    public Optional<ChatSession> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /**
     * 返回所有会话，按最后活跃时间降序（最近活跃在前）。
     *
     * <p>每次调用复制 values 并排序，会话量大时 O(n log n)；原型规模可接受。</p>
     */
    @Override
    public List<ChatSession> findAll() {
        List<ChatSession> list = new ArrayList<>(store.values());
        list.sort(Comparator.comparing(ChatSession::getLastActiveAt).reversed());
        return list;
    }

    /**
     * 删除会话；id 不存在时静默忽略（幂等）。
     *
     * @param id 待删除的 sessionId
     */
    @Override
    public void delete(String id) {
        store.remove(id);
    }
}
