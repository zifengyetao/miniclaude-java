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
 * 启动时补齐场景所需的内置 Agent 定义。
 *
 * <p>名称必须与 RolePack 目录查找一致；仅在缺失时创建，避免重启产生重复定义。
 * 普通试点为 L2，而受监管风控和交易被硬限制为 L1 且仅允许 GRAPH：它们只能进行
 * 低风险、受控的配置/提示级演进，不能自主修改策略、审批图或增加外部动作能力。</p>
 */
public class PlatformSeedConfig {

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

    private static boolean missing(AgentPlatformService platform, String name) {
        return platform.listAgents().stream().noneMatch(agent -> agent.getName().equals(name));
    }
}
