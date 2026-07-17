package com.miniclaude.interfaces.rest;

import com.miniclaude.application.scenario.PilotScenarioService;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.scenario.RolePack;
import com.miniclaude.domain.scenario.ScenarioArtifact;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scenarios")
/**
 * 试点场景的 REST 边界。
 *
 * <p>控制器只负责 HTTP 参数归一化和路由，具体的工作区隔离、SQL guard、PII 脱敏、
 * 人工转接及租户校验均由应用服务执行。返回的是运行状态和场景制品，不暗示已创建
 * 外部 PR、执行真实数据库查询或发送客服回复。</p>
 */
public class PilotScenarioController {
    private final PilotScenarioService scenarios;

    public PilotScenarioController(PilotScenarioService scenarios) { this.scenarios = scenarios; }

    @GetMapping("/templates")
    public List<RolePack> templates() { return scenarios.templates(); }

    @PostMapping("/{scenario}/start")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentRun start(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @PathVariable String scenario,
            @RequestBody(required = false) Map<String, Object> input) {
        return scenarios.start(tenant, scenario, input == null ? Collections.emptyMap() : input);
    }

    @GetMapping("/{scenario}/runs/{runId}")
    public AgentRun status(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @PathVariable String scenario, @PathVariable String runId) {
        scenarios.templates().stream().filter(p -> p.getId().equals(scenario)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("scenario template not found: " + scenario));
        return scenarios.status(tenant, runId);
    }

    @GetMapping("/{scenario}/runs/{runId}/artifacts")
    public List<ScenarioArtifact> artifacts(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @PathVariable String scenario, @PathVariable String runId) {
        return statusAndArtifacts(tenant, scenario, runId);
    }

    @PostMapping("/{scenario}/runs/{runId}/continue")
    public AgentRun continueRun(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @PathVariable String scenario, @PathVariable String runId) {
        return scenarios.continueRun(tenant, scenario, runId);
    }

    private List<ScenarioArtifact> statusAndArtifacts(String tenant, String scenario, String runId) {
        // 先复用 status 校验场景与租户，再读取制品，避免跨租户仅凭 runId 枚举数据。
        status(tenant, scenario, runId);
        return scenarios.artifacts(tenant, runId);
    }
}
