package com.miniclaude.infrastructure.config;

import com.miniclaude.application.platform.AgentPlatformService;
import com.miniclaude.domain.platform.AgentDefinition;
import com.miniclaude.domain.platform.ExecutionMode;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;

@Configuration
/**
 * 平台启动种子数据：补齐内置场景所需的 {@link AgentDefinition} 模板。
 *
 * <p><b>触发时机</b>：Spring {@link CommandLineRunner}，应用就绪后执行一次。</p>
 *
 * <p><b>幂等策略</b>：按<b>名称</b>判断是否已存在（{@link #missing}），存在则跳过。
 * 避免 Flyway 清库后重复插入，也避免每次重启产生重复定义。</p>
 *
 * <p><b>进化级别约束（为何 L1 vs L2）</b>：
 * <ul>
 *   <li>试点场景（Coding/分析/客服）：{@link AgentDefinition.EvolutionLevel#L2}——
 *       允许受控的配置/提示级自进化，仍禁止 L4 与策略图篡改</li>
 *   <li>监管场景（风控/交易）：{@link AgentDefinition.RiskLevel#REGULATED} +
 *       {@link AgentDefinition.EvolutionLevel#L1} + 仅 {@link ExecutionMode#GRAPH}——
 *       硬限制进化不能引入 submit/处置能力；与 Fake 适配器、四眼审批链路对齐</li>
 * </ul></p>
 *
 * <p><b>名称必须与 RolePack 目录查找一致</b>，否则场景服务无法绑定员工定义。</p>
 */
public class PlatformSeedConfig {

    /**
     * 注册 CommandLineRunner Bean，在启动时种子化 Agent 模板。
     *
     * @param platform 平台应用服务，封装 createAgent / listAgents
     */
    @Bean
    CommandLineRunner seedPlatformTemplates(AgentPlatformService platform) {
        return args -> {
            if (missing(platform, "Coding Agent")) {
                platform.createAgent(
                        "Coding Agent",
                        "在隔离工作区中规划、修改、测试并生成可审查的代码变更。",
                        "软件工程师",
                        AgentDefinition.RiskLevel.MEDIUM,
                        AgentDefinition.EvolutionLevel.L2,
                        EnumSet.allOf(ExecutionMode.class));
            }
            if (missing(platform, "数据分析师")) {
                platform.createAgent(
                        "数据分析师",
                        "基于授权数据生成带口径、证据和可复现步骤的分析。",
                        "数据分析师",
                        AgentDefinition.RiskLevel.MEDIUM,
                        AgentDefinition.EvolutionLevel.L2,
                        EnumSet.of(ExecutionMode.CHAT, ExecutionMode.PLAN_EXECUTE, ExecutionMode.GRAPH));
            }
            if (missing(platform, "智能客服")) {
                platform.createAgent(
                        "智能客服",
                        "检索企业知识并生成合规回复草稿，必要时转交人工。",
                        "客户服务专员",
                        AgentDefinition.RiskLevel.MEDIUM,
                        AgentDefinition.EvolutionLevel.L2,
                        EnumSet.of(ExecutionMode.CHAT, ExecutionMode.GRAPH));
            }
            if (missing(platform, "风控调查 Agent")) {
                // 风控只给建议，REGULATED + L1 防止演进扩大为自动不利决定。
                platform.createAgent(
                        "风控调查 Agent",
                        "仅在独立仿真域生成 REVIEW/ESCALATE 调查建议，禁止自动不利决定。",
                        "受监管风控调查员",
                        AgentDefinition.RiskLevel.REGULATED,
                        AgentDefinition.EvolutionLevel.L1,
                        EnumSet.of(ExecutionMode.GRAPH));
            }
            if (missing(platform, "交易辅助 Agent")) {
                // 交易只产出草稿；L1 与只读端口共同确保演进不能引入 submit 能力。
                platform.createAgent(
                        "交易辅助 Agent",
                        "基于只读确定性 fake 数据生成交易提案及 OMS 草稿，永不提交订单。",
                        "受监管交易辅助员",
                        AgentDefinition.RiskLevel.REGULATED,
                        AgentDefinition.EvolutionLevel.L1,
                        EnumSet.of(ExecutionMode.GRAPH));
            }
        };
    }

    /**
     * 按显示名称判断是否尚无同名 Agent 定义。
     *
     * @param platform 平台服务
     * @param name     种子模板名称（与 RolePack 对齐）
     */
    private static boolean missing(AgentPlatformService platform, String name) {
        return platform.listAgents().stream().noneMatch(agent -> agent.getName().equals(name));
    }
}
