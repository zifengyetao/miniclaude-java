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

/**
 * 试点场景（Coding / 数据分析 / 客服）REST 边界。
 *
 * <p><b>职责</b>：HTTP 参数归一化与路由；工作区隔离、SQL guard、PII 脱敏、人工转接及
 * 租户校验均由 {@link PilotScenarioService} 执行。
 * <p><b>上游</b>：试点客户端；租户头默认 {@code default}。
 * <b>下游</b>：{@link PilotScenarioService}。
 * <p><b>安全/约束</b>：返回运行状态与场景制品，不暗示已创建外部 PR、执行真实 DB 查询或
 * 发送 CRM 回复；artifacts 查询前先校验场景与租户，防 runId 枚举。
 */
@RestController
@RequestMapping("/api/v1/scenarios")
public class PilotScenarioController {
    private final PilotScenarioService scenarios;

    public PilotScenarioController(PilotScenarioService scenarios) { this.scenarios = scenarios; }

    /** 列出试点场景 RolePack 模板。 */
    @GetMapping("/templates")
    public List<RolePack> templates() { return scenarios.templates(); }

    /**
     * 启动指定试点场景 Run。
     *
     * @param scenario 路径中的场景 ID，须存在于模板目录
     * @param input    场景相关输入 JSON，可为空
     */
    @PostMapping("/{scenario}/start")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentRun start(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @PathVariable String scenario,
            @RequestBody(required = false) Map<String, Object> input) {
        return scenarios.start(tenant, scenario, input == null ? Collections.emptyMap() : input);
    }

    /**
     * 查询 Run 状态；会先校验 scenario 模板存在且租户匹配。
     *
     * @throws IllegalArgumentException 场景模板不存在或租户不匹配
     */
    @GetMapping("/{scenario}/runs/{runId}")
    public AgentRun status(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @PathVariable String scenario, @PathVariable String runId) {
        scenarios.templates().stream().filter(p -> p.getId().equals(scenario)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("scenario template not found: " + scenario));
        return scenarios.status(tenant, runId);
    }

    /**
     * 列出 Run 制品；内部先调用 {@link #status} 做租户/场景校验。
     */
    @GetMapping("/{scenario}/runs/{runId}/artifacts")
    public List<ScenarioArtifact> artifacts(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @PathVariable String scenario, @PathVariable String runId) {
        return statusAndArtifacts(tenant, scenario, runId);
    }

    /**
     * 继续 Run（仅 data-analyst 场景在成本审批通过后可恢复）。
     */
    @PostMapping("/{scenario}/runs/{runId}/continue")
    public AgentRun continueRun(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant,
            @PathVariable String scenario, @PathVariable String runId) {
        return scenarios.continueRun(tenant, scenario, runId);
    }

    /**
     * 校验后读取制品，避免跨租户仅凭 runId 枚举数据。
     */
    private List<ScenarioArtifact> statusAndArtifacts(String tenant, String scenario, String runId) {
        // 先复用 status 校验场景与租户，再读取制品，避免跨租户仅凭 runId 枚举数据。
        status(tenant, scenario, runId);
        return scenarios.artifacts(tenant, runId);
    }
}
