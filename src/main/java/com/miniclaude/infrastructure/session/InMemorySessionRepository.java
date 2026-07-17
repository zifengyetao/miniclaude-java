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
 * 内存会话仓储实现。
 * <p>
 * 数据仅存于进程生命周期内，重启后丢失；适用于开发与单机部署场景。
 */
@Repository
public class InMemorySessionRepository implements SessionRepository {

    /** 线程安全的会话存储，键为 sessionId。 */
    private final ConcurrentHashMap<String, ChatSession> store = new ConcurrentHashMap<>();

    @Override
    public ChatSession save(ChatSession session) {
        store.put(session.getId(), session);
        return session;
    }

    @Override
    public Optional<ChatSession> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<ChatSession> findAll() {
        List<ChatSession> list = new ArrayList<>(store.values());
        list.sort(Comparator.comparing(ChatSession::getLastActiveAt).reversed());
        return list;
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }
}
