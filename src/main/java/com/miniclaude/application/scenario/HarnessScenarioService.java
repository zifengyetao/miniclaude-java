package com.miniclaude.application.scenario;

import com.miniclaude.domain.runtime.AgentHarness;
import com.miniclaude.domain.runtime.ExecutionContext;
import org.springframework.stereotype.Service;

/**
 * 三类场景进入共享 Harness 的应用入口。
 *
 * <p>Phase H2 暂不替换现有 REST 主链；部署方注册版本化 HarnessModelGateway 路由后，
 * 可由灰度入口调用本服务并与现有 Scenario 结果做 Shadow 对比。</p>
 */
@Service
public class HarnessScenarioService {
    private final AgentHarness harness;
    private final HarnessProfileCatalog profiles;

    public HarnessScenarioService(AgentHarness harness, HarnessProfileCatalog profiles) {
        this.harness = harness;
        this.profiles = profiles;
    }

    public AgentHarness.Result run(String scenario, ExecutionContext context,
                                   String model, String goal) {
        if (!ScenarioCatalog.ANALYST.equals(scenario)
                && !ScenarioCatalog.SUPPORT.equals(scenario)
                && !ScenarioCatalog.CODING.equals(scenario)) {
            throw new IllegalArgumentException("scenario has no harness profile: " + scenario);
        }
        return harness.run(new AgentHarness.Request(
                context, profiles.get(scenario), model, goal));
    }
}
