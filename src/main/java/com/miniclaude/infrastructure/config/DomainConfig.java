package com.miniclaude.infrastructure.config;

import com.miniclaude.domain.agent.AgentSettings;
import com.miniclaude.infrastructure.engine.CliMain;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 领域层 Bean 装配配置。
 *
 * <p><b>职责</b>：把 Spring 外部化配置（{@link MiniClaudeProperties}）与 CLI 引擎遗留的
 * 环境变量解析逻辑（{@link CliMain#resolveApiConfig}）合并为领域对象
 * {@link AgentSettings}，供 Chat 与运行时统一消费。</p>
 *
 * <p><b>为何复用 CliMain 解析</b>：{@code infrastructure/engine} 最初为 CLI 设计，
 * 已内置 MOONSHOT / OPENAI / ANTHROPIC 等环境变量的优先级链。HTTP 模式通过本配置
 * 复用同一套解析，避免「CLI 能连、HTTP 不能连」的配置漂移。</p>
 *
 * <p><b>安全提示</b>：{@link AgentSettings#getApiKey()} 可能来自环境变量或
 * {@code miniclaude.api-key}；该值<b>不得</b>写入仓库或日志。详见 {@code docs/security.md}。</p>
 */
@Configuration
@EnableConfigurationProperties(MiniClaudeProperties.class)
public class DomainConfig {

    /**
     * 构建全局 Agent 默认配置 Bean。
     *
     * <p><b>配置优先级（高 → 低）</b>：
     * <ol>
     *   <li>{@code application.yml} 中 {@code miniclaude.*} 显式配置</li>
     *   <li>{@link CliMain#resolveApiConfig} 从环境变量推断（MOONSHOT_API_KEY 等）</li>
     *   <li>硬编码兜底（如默认模型 {@code kimi-k2.6}、权限模式 {@code default}）</li>
     * </ol></p>
     *
     * <p><b>OpenAI 兼容判定</b>：只要 {@code apiBase} 非空即视为 OpenAI 兼容端点
     * （Moonshot 等），否则走 Anthropic 原生路径。这与 CLI 行为一致。</p>
     *
     * @param props 绑定 {@code miniclaude.*} 前缀的配置属性
     * @return 不可变领域设置，注入到 {@link com.miniclaude.application.chat.ChatApplicationService} 等
     */
    @Bean
    public AgentSettings agentSettings(MiniClaudeProperties props) {
        // 构造空 CLI 参数，仅用于触发 resolveApiConfig 的环境变量扫描
        CliMain.CliArgs empty = new CliMain.CliArgs();
        // 若 yml 显式指定 apiBase，优先写入 CLI 参数再解析，保证 yml > 环境变量
        if (StringUtils.hasText(props.getApiBase())) {
            empty.apiBase = props.getApiBase();
        }
        CliMain.ApiConfig api = CliMain.resolveApiConfig(empty);

        // 模型：yml 覆盖 > 提供商默认 > kimi-k2.6 兜底
        String model = StringUtils.hasText(props.getModel())
                ? props.getModel()
                : (api.defaultModel != null ? api.defaultModel : "kimi-k2.6");

        // API Key / Base：yml 非空则用 yml，否则沿用 CLI 环境变量解析结果
        String apiKey = StringUtils.hasText(props.getApiKey()) ? props.getApiKey() : api.apiKey;
        String apiBase = StringUtils.hasText(props.getApiBase()) ? props.getApiBase() : api.apiBase;
        // 有 base URL 即走 OpenAI 兼容 HTTP 客户端路径
        boolean useOpenAi = apiBase != null && !apiBase.isEmpty();

        // 工具权限模式：HTTP 场景下 LegacyAgentRuntime 会强制 confirmFn=false，
        // 但 permissionMode 仍影响引擎内部工具白名单行为
        String permission = StringUtils.hasText(props.getPermissionMode())
                ? props.getPermissionMode()
                : "default";

        return AgentSettings.builder()
                .model(model)
                .permissionMode(permission)
                .apiKey(apiKey)
                .apiBase(apiBase)
                .useOpenAiCompatible(useOpenAi)
                .thinking(props.isThinking())
                .maxCostUsd(props.getMaxCostUsd())
                .maxTurns(props.getMaxTurns())
                .workingDirectory(props.getWorkingDirectory())
                .build();
    }
}
