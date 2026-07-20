package com.miniclaude.infrastructure.platform;

import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.AgentRunRepository;
import com.miniclaude.domain.platform.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 使用 JDBC 表 {@code agent_run} 实现 {@link AgentRunRepository}。
 *
 * <p><b>数据含义</b>：{@link AgentRun} 是数字员工/场景 Run 的<b>聚合根快照</b>——
 * 状态、步数、成本、超时等当前视图；完整历史由 {@code run_event} / {@code run_checkpoint}
 * 表承载（见 {@link JdbcDurableStore}）。</p>
 *
 * <p><b>Upsert 策略</b>：先 UPDATE 后 INSERT（非 MERGE），兼容 H2/PostgreSQL 且实现简单。
 * 事务原子性依赖调用方 {@code @Transactional}；本方法自身不开启事务。</p>
 *
 * <p><b>并发 caveat</b>：虽持久化 {@code version} 字段，但 UPDATE SQL 未带
 * {@code WHERE version=?} 乐观锁——并发写为「最后写入生效」。乐观锁在
 * {@link LocalDurableOrchestrator} 层 enforced。</p>
 */
@Repository
public class JdbcAgentRunRepository implements AgentRunRepository {

    private final JdbcTemplate jdbc;

    /** ResultSet → {@link AgentRun} 行映射；枚举字段按 name() 存取 */
    private final RowMapper<AgentRun> rowMapper = (rs, rowNum) -> new AgentRun(
            rs.getString("id"),
            rs.getString("agent_id"),
            ExecutionMode.valueOf(rs.getString("execution_mode")),
            rs.getString("goal"),
            AgentRun.Status.valueOf(rs.getString("status")),
            rs.getInt("current_step"),
            rs.getInt("max_steps"),
            rs.getBigDecimal("max_cost_usd"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getString("tenant_id"),
            rs.getLong("version"),
            rs.getBigDecimal("cost_usd"),
            rs.getTimestamp("timeout_at") == null ? null : rs.getTimestamp("timeout_at").toInstant());

    public JdbcAgentRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按运行标识更新快照，未命中时插入。数据库失败向上传播；对现有记录重复保存同一
     * 快照结果等价，但并发更新遵循最后写入生效，并发首次插入可能发生唯一键冲突。
     */
    @Override
    public AgentRun save(AgentRun run) {
        int updated = jdbc.update(
                "UPDATE agent_run SET status=?, current_step=?, max_steps=?, max_cost_usd=?, "
                        + "updated_at=?, tenant_id=?, version=?, cost_usd=?, timeout_at=? "
                        + "WHERE id=?",
                run.getStatus().name(),
                run.getCurrentStep(),
                run.getMaxSteps(),
                run.getMaxCostUsd(),
                Timestamp.from(run.getUpdatedAt()),
                run.getTenantId(),
                run.getVersion(),
                run.getCostUsd(),
                run.getTimeoutAt() == null ? null : Timestamp.from(run.getTimeoutAt()),
                run.getId());
        // 更新未命中才进入插入路径；调用方事务决定两条语句之间是否可被并发观察。
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO agent_run "
                            + "(id,agent_id,execution_mode,goal,status,current_step,max_steps,max_cost_usd,"
                            + "created_at,updated_at,tenant_id,version,cost_usd,timeout_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    run.getId(),
                    run.getAgentId(),
                    run.getExecutionMode().name(),
                    run.getGoal(),
                    run.getStatus().name(),
                    run.getCurrentStep(),
                    run.getMaxSteps(),
                    run.getMaxCostUsd(),
                    Timestamp.from(run.getCreatedAt()),
                    Timestamp.from(run.getUpdatedAt()),
                    run.getTenantId(),
                    run.getVersion(),
                    run.getCostUsd(),
                    run.getTimeoutAt() == null ? null : Timestamp.from(run.getTimeoutAt()));
        }
        return run;
    }

    /** 按主键 id 查询；不存在返回 {@link Optional#empty()} */
    @Override
    public Optional<AgentRun> findById(String id) {
        List<AgentRun> result = jdbc.query(
                "SELECT * FROM agent_run WHERE id=?",
                rowMapper,
                id);
        return result.stream().findFirst();
    }

    /** 全表列表，按创建时间降序（工作台「最近运行」视图） */
    @Override
    public List<AgentRun> findAll() {
        return jdbc.query("SELECT * FROM agent_run ORDER BY created_at DESC", rowMapper);
    }
}
