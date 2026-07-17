package com.miniclaude.domain.platform;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 可版本化发布的数字员工定义聚合。
 *
 * <p>本类保存身份、风险、演进等级和允许的执行模式，不负责发布状态迁移或持久化。
 * 实例不可变；执行模式会被防御性复制，因而可作为跨层返回值安全共享。
 */
public final class AgentDefinition {

    public enum RiskLevel { LOW, MEDIUM, HIGH, REGULATED }
    public enum EvolutionLevel { L0, L1, L2, L3 }
    public enum Status { DRAFT, ACTIVE, DEPRECATED, REVOKED }

    private final String id;
    private final String name;
    private final String description;
    private final String roleName;
    private final RiskLevel riskLevel;
    private final EvolutionLevel evolutionLevel;
    private final Status status;
    private final String version;
    private final Set<ExecutionMode> executionModes;
    private final Instant createdAt;
    private final Instant updatedAt;

    public AgentDefinition(
            String id,
            String name,
            String description,
            String roleName,
            RiskLevel riskLevel,
            EvolutionLevel evolutionLevel,
            Status status,
            String version,
            Set<ExecutionMode> executionModes,
            Instant createdAt,
            Instant updatedAt) {
        this.id = requireText(id, "id");
        this.name = requireText(name, "name");
        this.description = requireText(description, "description");
        this.roleName = requireText(roleName, "roleName");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        this.evolutionLevel = Objects.requireNonNull(evolutionLevel, "evolutionLevel");
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireText(version, "version");
        if (executionModes == null || executionModes.isEmpty()) {
            throw new IllegalArgumentException("executionModes must not be empty");
        }
        this.executionModes = Collections.unmodifiableSet(EnumSet.copyOf(executionModes));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * 创建具有新标识和初始版本的草稿。
     *
     * <p>名称、描述、角色、等级及至少一个执行模式必须有效，否则构造过程会失败。
     * 每次调用都会生成新的 UUID 和时间戳，因此该工厂方法并非幂等；并发调用彼此独立。
     */
    public static AgentDefinition draft(
            String name,
            String description,
            String roleName,
            RiskLevel riskLevel,
            EvolutionLevel evolutionLevel,
            Set<ExecutionMode> executionModes) {
        Instant now = Instant.now();
        return new AgentDefinition(
                UUID.randomUUID().toString(),
                name,
                description,
                roleName,
                riskLevel,
                evolutionLevel,
                Status.DRAFT,
                "0.1.0",
                executionModes,
                now,
                now);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getRoleName() { return roleName; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public EvolutionLevel getEvolutionLevel() { return evolutionLevel; }
    public Status getStatus() { return status; }
    public String getVersion() { return version; }
    public Set<ExecutionMode> getExecutionModes() { return executionModes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
