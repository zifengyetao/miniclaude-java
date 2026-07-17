package com.miniclaude.infrastructure.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporal 可选边界的连接配置。
 *
 * <p>只有显式把 {@code platform.orchestrator} 设为 {@code temporal} 才创建客户端和连接，
 * 默认本地模式不会因类路径中存在 Temporal SDK 而产生外部依赖。显式选择后若服务不可用，
 * 连接或调用会直接失败，不静默回退到语义不同的本地编排。</p>
 */
@Configuration
@ConditionalOnProperty(name = "platform.orchestrator", havingValue = "temporal")
public class TemporalBoundaryConfiguration {
    /** 创建受 Spring 生命周期管理的服务连接，容器关闭时释放网络资源。 */
    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs temporalService(
            @Value("${platform.temporal.target:127.0.0.1:7233}") String target) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(target).build());
    }

    /** 创建绑定到指定 namespace 的 Workflow 客户端，隔离不同部署的执行历史。 */
    @Bean
    WorkflowClient temporalClient(
            WorkflowServiceStubs service,
            @Value("${platform.temporal.namespace:default}") String namespace) {
        return WorkflowClient.newInstance(service,
                io.temporal.client.WorkflowClientOptions.newBuilder()
                        .setNamespace(namespace).build());
    }
}
