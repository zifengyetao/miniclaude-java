package com.miniclaude.domain.platform;

import java.util.List;
import java.util.Optional;

/**
 * 数字员工定义的领域持久化端口。
 * <p>
 * <b>为何放在 domain：</b>{@link AgentDefinition} 是平台 Registry 的核心聚合，持久化契约属于领域。
 * <p>
 * <b>不变量：</b>不规定存储介质、发布状态机推进或并发冲突策略；这些由 application 治理服务 + 适配器实现。
 * <p>
 * <b>边界：</b>
 * <ul>
 *   <li><b>application</b>：创建草稿、发布、废弃员工定义。</li>
 *   <li><b>infrastructure</b>：Flyway V3+ 表 + JDBC 实现。</li>
 * </ul>
 */
public interface AgentDefinitionRepository {

    /**
     * 保存或更新员工定义。
     *
     * @param definition 不可变定义值对象
     * @return 持久化后的定义
     */
    AgentDefinition save(AgentDefinition definition);

    /**
     * 按 ID 查询定义。
     *
     * @param id 定义 ID
     * @return 存在则返回
     */
    Optional<AgentDefinition> findById(String id);

    /**
     * 列出全部定义。
     *
     * @return 定义列表
     */
    List<AgentDefinition> findAll();
}
