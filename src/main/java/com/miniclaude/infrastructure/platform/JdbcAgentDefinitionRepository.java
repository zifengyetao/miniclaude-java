package com.miniclaude.infrastructure.platform;

import com.miniclaude.domain.platform.AgentDefinition;
import com.miniclaude.domain.platform.AgentDefinitionRepository;
import com.miniclaude.domain.platform.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 使用 JDBC 表 {@code agent_definition} 实现员工定义仓储。
 *
 * <p>适配器负责领域枚举、执行模式集合与关系字段的双向映射，不承担领域校验。
 * 写入采用先更新后插入的兼容式 upsert，原子性依赖调用方事务；并发首次写入同一标识
 * 可能触发数据库唯一键冲突。
 */
@Repository
public class JdbcAgentDefinitionRepository implements AgentDefinitionRepository {

    private final JdbcTemplate jdbc;

    private final RowMapper<AgentDefinition> rowMapper = (rs, rowNum) -> new AgentDefinition(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("role_name"),
            AgentDefinition.RiskLevel.valueOf(rs.getString("risk_level")),
            AgentDefinition.EvolutionLevel.valueOf(rs.getString("evolution_level")),
            AgentDefinition.Status.valueOf(rs.getString("status")),
            rs.getString("version"),
            parseModes(rs.getString("execution_modes")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    public JdbcAgentDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按标识更新定义，未命中时插入。参数必须是有效领域对象；数据库约束或连接失败向上传播。
     * 对已存在记录重复保存同一快照结果等价，但首次并发保存不保证无冲突。
     */
    @Override
    public AgentDefinition save(AgentDefinition definition) {
        int updated = jdbc.update(
                "UPDATE agent_definition SET name=?, description=?, role_name=?, risk_level=?, "
                        + "evolution_level=?, status=?, version=?, execution_modes=?, updated_at=? WHERE id=?",
                definition.getName(),
                definition.getDescription(),
                definition.getRoleName(),
                definition.getRiskLevel().name(),
                definition.getEvolutionLevel().name(),
                definition.getStatus().name(),
                definition.getVersion(),
                formatModes(definition.getExecutionModes()),
                Timestamp.from(definition.getUpdatedAt()),
                definition.getId());
        // 只有更新未命中才插入，兼顾新建和保存快照；事务负责避免中间状态泄露。
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO agent_definition "
                            + "(id,name,description,role_name,risk_level,evolution_level,status,version,"
                            + "execution_modes,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    definition.getId(),
                    definition.getName(),
                    definition.getDescription(),
                    definition.getRoleName(),
                    definition.getRiskLevel().name(),
                    definition.getEvolutionLevel().name(),
                    definition.getStatus().name(),
                    definition.getVersion(),
                    formatModes(definition.getExecutionModes()),
                    Timestamp.from(definition.getCreatedAt()),
                    Timestamp.from(definition.getUpdatedAt()));
        }
        return definition;
    }

    @Override
    public Optional<AgentDefinition> findById(String id) {
        List<AgentDefinition> result = jdbc.query(
                "SELECT * FROM agent_definition WHERE id=?",
                rowMapper,
                id);
        return result.stream().findFirst();
    }

    @Override
    public List<AgentDefinition> findAll() {
        return jdbc.query("SELECT * FROM agent_definition ORDER BY updated_at DESC", rowMapper);
    }

    private static String formatModes(Set<ExecutionMode> modes) {
        return modes.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static Set<ExecutionMode> parseModes(String value) {
        EnumSet<ExecutionMode> result = EnumSet.noneOf(ExecutionMode.class);
        if (value != null && !value.trim().isEmpty()) {
            Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(ExecutionMode::valueOf)
                    .forEach(result::add);
        }
        return result;
    }
}
