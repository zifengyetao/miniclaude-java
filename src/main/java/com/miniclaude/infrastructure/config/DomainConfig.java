package com.miniclaude.infrastructure.config;

import com.miniclaude.domain.agent.AgentSettings;
import com.miniclaude.infrastructure.engine.CliMain;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 领域层 Bean 装配。
 * <p>
 * 将 Spring 配置与环境变量合并为领域可用的 {@link AgentSettings} 默认值。
 */
@Configuration
@EnableConfigurationProperties(MiniClaudeProperties.class)
public class DomainConfig {

    /**
     * 构建全局 Agent 默认配置。
     * <p>
     * 优先级：{@code miniclaude.*} 配置项 &gt; 环境变量 &gt; 内置兜底值。
     */
    @Bean
    public AgentSettings agentSettings(MiniClaudeProperties props) {
        CliMain.CliArgs empty = new CliMain.CliArgs();
        if (StringUtils.hasText(props.getApiBase())) {
            empty.apiBase = props.getApiBase();
        }
        CliMain.ApiConfig api = CliMain.resolveApiConfig(empty);

        String model = StringUtils.hasText(props.getModel())
                ? props.getModel()
                : (api.defaultModel != null ? api.defaultModel : "kimi-k2.6");

        String apiKey = StringUtils.hasText(props.getApiKey()) ? props.getApiKey() : api.apiKey;
        String apiBase = StringUtils.hasText(props.getApiBase()) ? props.getApiBase() : api.apiBase;
        boolean useOpenAi = apiBase != null && !apiBase.isEmpty();

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
