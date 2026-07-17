package com.miniclaude.domain.platform;

import java.util.List;
import java.util.Optional;

/**
 * 数字员工定义的领域持久化端口。
 *
 * <p>接口不规定存储介质、事务或并发冲突策略；这些语义由应用事务边界和具体适配器提供。
 */
public interface AgentDefinitionRepository {
    AgentDefinition save(AgentDefinition definition);
    Optional<AgentDefinition> findById(String id);
    List<AgentDefinition> findAll();
}
