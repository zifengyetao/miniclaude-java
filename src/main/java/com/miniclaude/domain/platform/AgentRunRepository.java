package com.miniclaude.domain.platform;

import java.util.List;
import java.util.Optional;

/**
 * Agent 运行快照的领域持久化端口。
 *
 * <p>端口只定义保存与查询能力，不承诺乐观锁、幂等插入或排序规则；具体保证属于适配器边界。
 */
public interface AgentRunRepository {
    AgentRun save(AgentRun run);
    Optional<AgentRun> findById(String id);
    List<AgentRun> findAll();
}
