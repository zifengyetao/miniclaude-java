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
 * 使用 JDBC 表 {@code agent_definition} 实现 {@link AgentDefinitionRepository}。
 *
 * <p><b>Agent 定义</b>：数字员工模板——名称、角色、风险等级、进化级别 L0–L3、
 * 允许的执行模式（CHAT / PLAN_EXECUTE / GRAPH）等。场景 RolePack 通过 name 关联定义。</p>
 *
 * <p><b>execution_modes 序列化</b>：库内以逗号分隔、字母序存储（{@link #formatModes}），
 * 读取时解析为 {@link EnumSet}（{@link #parseModes}）。空串表示无模式——由领域层校验拒绝。</p>
 */
@Repository
public class JdbcAgentDefinitionRepository implements AgentDefinitionRepository {

    private final JdbcTemplate jdbc;

    /** 行映射：{@code execution_modes} 列经 {@link #parseModes} 转为枚举集合 */
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

    /** 全部定义，按 updated_at 降序 */
    @Override
    public List<AgentDefinition> findAll() {
        return jdbc.query("SELECT * FROM agent_definition ORDER BY updated_at DESC", rowMapper);
    }

    /** 将执行模式集合序列化为稳定排序的逗号串，便于 diff 与人工阅读 */
    private static String formatModes(Set<ExecutionMode> modes) {
        return modes.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    /**
     * 从库内逗号串解析执行模式；非法枚举名会在 {@link ExecutionMode#valueOf} 抛异常。
     *
     * <p>空/null 输入返回空 {@link EnumSet}，由上层决定是否合法。</p>
     */
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
