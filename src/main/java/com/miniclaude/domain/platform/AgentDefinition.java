package com.miniclaude.domain.platform;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 可版本化发布的数字员工定义聚合（不可变值对象）。
 * <p>
 * <b>为何放在 domain：</b>员工身份、风险等级、进化上限与允许执行模式是平台 Registry 核心语义，
 * 与 HTTP/DB 无关。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@code executionModes} 非空；防御性复制为不可变 EnumSet。</li>
 *   <li>所有文本字段非空白；{@code evolutionLevel} 监管场景不得超过 L3（application 额外约束 L4 禁止）。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application 治理服务推进 {@link Status}；infrastructure 持久化到 registry 表。
 */
public final class AgentDefinition {

    /** 业务风险分级，影响策略与审批要求。 */
    public enum RiskLevel {
        /** 低风险：只读或建议型动作为主。 */
        LOW,
        /** 中风险：可能触发工具写操作，需策略判定。 */
        MEDIUM,
        /** 高风险：需人工审批或四眼原则。 */
        HIGH,
        /** 受监管：风控/交易仿真，受 {@link com.miniclaude.domain.scenario.RegulatedSimulationPolicy} 约束。 */
        REGULATED
    }

    /**
     * 自进化能力等级（L4 在平台层禁止）。
     * <ul>
     *   <li>L0：无自主进化，仅人工发布。</li>
     *   <li>L1：低风险提示/配置级候选（受监管域默认上限）。</li>
     *   <li>L2：技能/规则候选，须评测+评审。</li>
     *   <li>L3：Graph/Verifier 级候选，须 shadow+canary。</li>
     * </ul>
     */
    public enum EvolutionLevel { L0, L1, L2, L3 }

    /**
     * 定义发布生命周期。
     * <p>
     * <b>状态转移（application Registry 服务推进）：</b>
     * DRAFT ──发布校验通过──► ACTIVE ──废弃──► DEPRECATED ──撤销──► REVOKED
     * <br>ACTIVE 定义可用于新 Run；REVOKED 不可用于新 Run，历史 Run 仍引用旧 ID 审计。
     */
    public enum Status {
        /** 草稿，未对外提供服务。 */
        DRAFT,
        /** 已发布，可启动新 Run。 */
        ACTIVE,
        /** 已废弃，不推荐新 Run，历史仍可查。 */
        DEPRECATED,
        /** 已撤销，禁止新 Run。 */
        REVOKED
    }

    /** 定义唯一 ID。 */
    private final String id;
    /** 展示名称。 */
    private final String name;
    /** 描述文本。 */
    private final String description;
    /** 绑定的角色名（关联 RolePack / Prompt 资产）。 */
    private final String roleName;
    /** 风险等级。 */
    private final RiskLevel riskLevel;
    /** 允许的自进化等级上限。 */
    private final EvolutionLevel evolutionLevel;
    /** 发布状态。 */
    private final Status status;
    /** 语义化版本字符串（如 0.1.0）。 */
    private final String version;
    /** 允许的执行模式集合（不可变）。 */
    private final Set<ExecutionMode> executionModes;
    /** 创建时间。 */
    private final Instant createdAt;
    /** 更新时间。 */
    private final Instant updatedAt;

    /**
     * 全字段构造，校验文本非空与 executionModes 非空。
     */
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
        // 防御性复制，防止调用方修改传入 Set
        this.executionModes = Collections.unmodifiableSet(EnumSet.copyOf(executionModes));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * 创建具有新 UUID 和初始版本 0.1.0 的草稿（Status=DRAFT）。
     * <p>非幂等；每次调用独立生成新 ID。
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

    /** @return 定义 ID */
    public String getId() { return id; }
    /** @return 名称 */
    public String getName() { return name; }
    /** @return 描述 */
    public String getDescription() { return description; }
    /** @return 角色名 */
    public String getRoleName() { return roleName; }
    /** @return 风险等级 */
    public RiskLevel getRiskLevel() { return riskLevel; }
    /** @return 进化等级上限 */
    public EvolutionLevel getEvolutionLevel() { return evolutionLevel; }
    /** @return 发布状态 */
    public Status getStatus() { return status; }
    /** @return 版本号 */
    public String getVersion() { return version; }
    /** @return 不可变执行模式集合 */
    public Set<ExecutionMode> getExecutionModes() { return executionModes; }
    /** @return 创建时间 */
    public Instant getCreatedAt() { return createdAt; }
    /** @return 更新时间 */
    public Instant getUpdatedAt() { return updatedAt; }
}
