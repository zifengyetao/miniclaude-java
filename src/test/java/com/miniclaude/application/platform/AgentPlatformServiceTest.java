package com.miniclaude.application.platform;

import com.miniclaude.domain.platform.AgentDefinition;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.ExecutionMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:platform-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@ActiveProfiles("test")
class AgentPlatformServiceTest {

    @Autowired
    private AgentPlatformService platform;

    @Test
    void seedsTemplatesAndPersistsRun() {
        AgentDefinition coding = platform.listAgents().stream()
                .filter(agent -> "Coding Agent".equals(agent.getName()))
                .findFirst()
                .orElseThrow(AssertionError::new);

        AgentRun run = platform.startRun(
                coding.getId(),
                ExecutionMode.PLAN_EXECUTE,
                "修复失败测试并生成 PR 草稿",
                30,
                new BigDecimal("3.50"));

        assertThat(platform.getRun(run.getId()).getStatus()).isEqualTo(AgentRun.Status.PENDING);
        assertThat(platform.listRuns()).extracting(AgentRun::getId).contains(run.getId());
    }
}
