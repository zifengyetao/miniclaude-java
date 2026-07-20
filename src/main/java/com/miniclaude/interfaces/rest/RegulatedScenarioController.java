package com.miniclaude.interfaces.rest;

import com.miniclaude.application.scenario.RegulatedScenarioCatalog;
import com.miniclaude.application.scenario.RegulatedScenarioService;
import com.miniclaude.domain.durable.ApprovalRequest;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.scenario.RolePack;
import com.miniclaude.domain.scenario.ScenarioArtifact;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 受监管仿真场景的统一 REST 边界。
 *
 * <p><b>职责</b>：风控调查与交易辅助两条固定路径的 HTTP 映射；场景类型由 URI 推断，
 * 不允许请求体自选场景以 bypass 策略。
 * <p><b>上游</b>：监管试点客户端；{@code X-Tenant-Id} 必填。
 * <b>下游</b>：{@link RegulatedScenarioService}。
 * <p><b>安全/约束</b>：仅建议/草稿流程；无客户不利决定、无 OMS submit/placeOrder；
 * 四眼审批、kill switch、职责分离由服务层执行；start 支持 {@code Idempotency-Key}。
 */
@RestController
@RequestMapping("/api/v1/scenarios")
public final class RegulatedScenarioController {
    private final RegulatedScenarioService scenarios;

    public RegulatedScenarioController(RegulatedScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    /** 列出受监管场景 RolePack 模板。 */
    @GetMapping("/regulated/templates")
    public List<RolePack> templates() { return scenarios.templates(); }

    /**
     * 启动受监管仿真 Run（风控调查或交易辅助，由 URI 决定）。
     *
     * @param tenant         租户（必填）
     * @param idempotencyKey 可选幂等键，重试时返回同一 Run
     * @param input          场景输入；须含 {@code proposer} 等字段
     * @param request        用于从 URI 解析场景类型
     * @return 新建或幂等重放的 Run，HTTP 201
     */
    @PostMapping({"/risk-investigation/start", "/trading-assistant/start"})
    @ResponseStatus(HttpStatus.CREATED)
    public AgentRun start(
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> input,
            HttpServletRequest request) {
        return scenarios.start(tenant, scenario(request), input == null
                ? Collections.emptyMap() : input, idempotencyKey);
    }

    /** 查询 Run 状态（含租户校验）。 */
    @GetMapping({"/risk-investigation/runs/{runId}", "/trading-assistant/runs/{runId}"})
    public AgentRun status(@RequestHeader("X-Tenant-Id") String tenant,
                           @PathVariable String runId) {
        return scenarios.status(tenant, runId);
    }

    /** 列出 Run 产生的场景制品（案例包、提案、草稿等）。 */
    @GetMapping({"/risk-investigation/runs/{runId}/artifacts",
            "/trading-assistant/runs/{runId}/artifacts"})
    public List<ScenarioArtifact> artifacts(@RequestHeader("X-Tenant-Id") String tenant,
                                            @PathVariable String runId) {
        return scenarios.artifacts(tenant, runId);
    }

    /** 列出 Run 当前四眼审批阶段的所有审批请求。 */
    @GetMapping({"/risk-investigation/runs/{runId}/approval-stage",
            "/trading-assistant/runs/{runId}/approval-stage"})
    public List<ApprovalRequest> approvals(@RequestHeader("X-Tenant-Id") String tenant,
                                           @PathVariable String runId) {
        return scenarios.approvalStage(tenant, runId);
    }

    /**
     * 提交单条四眼审批决定。
     *
     * @param body 须含 {@code actor}、{@code decision}；可选 {@code reason}
     * @throws SecurityException 提案人自批、重复审批人等（403）
     */
    @PostMapping({"/risk-investigation/runs/{runId}/approvals/{approvalId}/decision",
            "/trading-assistant/runs/{runId}/approvals/{approvalId}/decision"})
    public ApprovalRequest decide(@RequestHeader("X-Tenant-Id") String tenant,
                                  @PathVariable String runId, @PathVariable String approvalId,
                                  @RequestBody Map<String, Object> body) {
        return scenarios.decide(tenant, runId, approvalId, required(body, "actor"),
                required(body, "decision"), text(body, "reason", ""));
    }

    /**
     * 四眼审批完成后继续 Run，生成最终建议或 OMS 草稿（仍不可提交）。
     *
     * @throws SecurityException 四眼条件未满足或 kill switch 激活
     */
    @PostMapping({"/risk-investigation/runs/{runId}/continue",
            "/trading-assistant/runs/{runId}/continue"})
    public AgentRun continueRun(@RequestHeader("X-Tenant-Id") String tenant,
                                @PathVariable String runId, HttpServletRequest request) {
        return scenarios.continueRun(tenant, scenario(request), runId);
    }

    /** 查询租户 kill switch 是否激活。 */
    @GetMapping("/regulated/kill-switch")
    public Map<String, Object> killSwitch(@RequestHeader("X-Tenant-Id") String tenant) {
        return Collections.singletonMap("active", scenarios.killSwitch(tenant));
    }

    /**
     * 设置租户 kill switch（紧急阻断新 Run 与恢复）。
     *
     * @param body 须含 {@code active}、{@code actor}
     */
    @PutMapping("/regulated/kill-switch")
    public Map<String, Object> setKillSwitch(@RequestHeader("X-Tenant-Id") String tenant,
                                             @RequestBody Map<String, Object> body) {
        boolean active = Boolean.parseBoolean(required(body, "active"));
        boolean value = scenarios.setKillSwitch(tenant, active, required(body, "actor"));
        return Collections.singletonMap("active", value);
    }

    /**
     * 从请求 URI 解析受监管场景 ID（仅两个固定值）。
     *
     * @throws IllegalArgumentException URI 不匹配已知场景路径
     * @implNote 映射只接受两个固定路径，不允许请求体自行指定并绕过相应场景策略
     */
    private static String scenario(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 映射只接受两个固定路径，不允许请求体自行指定并绕过相应场景策略。
        if (uri.contains("/" + RegulatedScenarioCatalog.INVESTIGATION + "/")) {
            return RegulatedScenarioCatalog.INVESTIGATION;
        }
        if (uri.contains("/" + RegulatedScenarioCatalog.TRADING + "/")) {
            return RegulatedScenarioCatalog.TRADING;
        }
        throw new IllegalArgumentException("regulated scenario not found");
    }

    private static String required(Map<String, Object> body, String name) {
        String value = text(body, name, null);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " required");
        return value.trim();
    }

    private static String text(Map<String, Object> body, String name, String fallback) {
        Object value = body == null ? null : body.get(name);
        return value == null ? fallback : String.valueOf(value);
    }
}
