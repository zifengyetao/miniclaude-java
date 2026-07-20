package com.miniclaude.infrastructure.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporal 可选边界的 Spring 配置。
 *
 * <p><b>为何 {@code @ConditionalOnProperty}</b>：
 * 默认 {@code platform.orchestrator=local} 不应因 classpath 存在 Temporal SDK 就
 * 连接 7233 端口或创建 gRPC 连接——否则本地/H2 开发环境会因 Temporal 未启动而失败。</p>
 *
 * <p><b>显式选择 temporal 后的行为</b>：连接/调用失败直接抛错，<b>不</b>静默回退
 * 到 {@link com.miniclaude.infrastructure.durable.LocalDurableOrchestrator}——
 * 两种编排器语义不同（Workflow 历史 vs JDBC 事务），混用会导致双写或状态不一致。</p>
 *
 * <p>配置项：{@code platform.temporal.target}（默认 127.0.0.1:7233）、
 * {@code platform.temporal.namespace}（默认 default）。</p>
 */
@Configuration
@ConditionalOnProperty(name = "platform.orchestrator", havingValue = "temporal")
public class TemporalBoundaryConfiguration {
    /**
     * gRPC 服务桩；{@code destroyMethod=shutdown} 确保容器关闭时释放连接。
     *
     * @param target Temporal Frontend 地址
     */
    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs temporalService(
            @Value("${platform.temporal.target:127.0.0.1:7233}") String target) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(target).build());
    }

    /**
     * Workflow 客户端，绑定 namespace 隔离不同环境/租户的执行历史。
     */
    @Bean
    WorkflowClient temporalClient(
            WorkflowServiceStubs service,
            @Value("${platform.temporal.namespace:default}") String namespace) {
        return WorkflowClient.newInstance(service,
                io.temporal.client.WorkflowClientOptions.newBuilder()
                        .setNamespace(namespace).build());
    }
}
