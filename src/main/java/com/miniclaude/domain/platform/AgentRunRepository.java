package com.miniclaude.domain.platform;

import java.util.List;
import java.util.Optional;

/**
 * Agent 运行快照的领域持久化端口。
 * <p>
 * <b>为何放在 domain：</b>{@link AgentRun} 是可恢复 Run 的领域模型，仓储契约属于领域层，
 * 不承诺 JDBC/SQL 细节。
 * <p>
 * <b>不变量：</b>端口不定义乐观锁、幂等插入或排序；并发与事务由 application + infrastructure 保证。
 * <p>
 * <b>边界：</b>
 * <ul>
 *   <li><b>application</b>：平台 Run 服务查询/更新快照。</li>
 *   <li><b>infrastructure</b>：JDBC 实现，与 {@link com.miniclaude.domain.durable.DurableOrchestrator} 协同。</li>
 * </ul>
 */
public interface AgentRunRepository {

    /**
     * 保存或更新运行快照。
     *
     * @param run 不可变运行值对象
     * @return 持久化后的 run（可能含存储层字段）
     */
    AgentRun save(AgentRun run);

    /**
     * 按 ID 查询运行。
     *
     * @param id 运行 ID
     * @return 存在则返回
     */
    Optional<AgentRun> findById(String id);

    /**
     * 列出全部运行（排序由适配器决定）。
     *
     * @return 运行列表
     */
    List<AgentRun> findAll();
}
