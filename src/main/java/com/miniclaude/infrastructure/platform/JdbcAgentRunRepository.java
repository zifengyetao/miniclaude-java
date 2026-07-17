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

@Repository
public class JdbcAgentRunRepository implements AgentRunRepository {

    private final JdbcTemplate jdbc;

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

    @Override
    public Optional<AgentRun> findById(String id) {
        List<AgentRun> result = jdbc.query(
                "SELECT * FROM agent_run WHERE id=?",
                rowMapper,
                id);
        return result.stream().findFirst();
    }

    @Override
    public List<AgentRun> findAll() {
        return jdbc.query("SELECT * FROM agent_run ORDER BY created_at DESC", rowMapper);
    }
}
