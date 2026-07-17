package com.miniclaude.infrastructure.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 只有显式选择 temporal 编排器时才建立连接。 */
@Configuration
@ConditionalOnProperty(name = "platform.orchestrator", havingValue = "temporal")
public class TemporalBoundaryConfiguration {
    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs temporalService(
            @Value("${platform.temporal.target:127.0.0.1:7233}") String target) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(target).build());
    }

    @Bean
    WorkflowClient temporalClient(
            WorkflowServiceStubs service,
            @Value("${platform.temporal.namespace:default}") String namespace) {
        return WorkflowClient.newInstance(service,
                io.temporal.client.WorkflowClientOptions.newBuilder()
                        .setNamespace(namespace).build());
    }
}
