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

@RestController
@RequestMapping("/api/v1/scenarios")
public final class RegulatedScenarioController {
    private final RegulatedScenarioService scenarios;

    public RegulatedScenarioController(RegulatedScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping("/regulated/templates")
    public List<RolePack> templates() { return scenarios.templates(); }

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

    @GetMapping({"/risk-investigation/runs/{runId}", "/trading-assistant/runs/{runId}"})
    public AgentRun status(@RequestHeader("X-Tenant-Id") String tenant,
                           @PathVariable String runId) {
        return scenarios.status(tenant, runId);
    }

    @GetMapping({"/risk-investigation/runs/{runId}/artifacts",
            "/trading-assistant/runs/{runId}/artifacts"})
    public List<ScenarioArtifact> artifacts(@RequestHeader("X-Tenant-Id") String tenant,
                                            @PathVariable String runId) {
        return scenarios.artifacts(tenant, runId);
    }

    @GetMapping({"/risk-investigation/runs/{runId}/approval-stage",
            "/trading-assistant/runs/{runId}/approval-stage"})
    public List<ApprovalRequest> approvals(@RequestHeader("X-Tenant-Id") String tenant,
                                           @PathVariable String runId) {
        return scenarios.approvalStage(tenant, runId);
    }

    @PostMapping({"/risk-investigation/runs/{runId}/approvals/{approvalId}/decision",
            "/trading-assistant/runs/{runId}/approvals/{approvalId}/decision"})
    public ApprovalRequest decide(@RequestHeader("X-Tenant-Id") String tenant,
                                  @PathVariable String runId, @PathVariable String approvalId,
                                  @RequestBody Map<String, Object> body) {
        return scenarios.decide(tenant, runId, approvalId, required(body, "actor"),
                required(body, "decision"), text(body, "reason", ""));
    }

    @PostMapping({"/risk-investigation/runs/{runId}/continue",
            "/trading-assistant/runs/{runId}/continue"})
    public AgentRun continueRun(@RequestHeader("X-Tenant-Id") String tenant,
                                @PathVariable String runId, HttpServletRequest request) {
        return scenarios.continueRun(tenant, scenario(request), runId);
    }

    @GetMapping("/regulated/kill-switch")
    public Map<String, Object> killSwitch(@RequestHeader("X-Tenant-Id") String tenant) {
        return Collections.singletonMap("active", scenarios.killSwitch(tenant));
    }

    @PutMapping("/regulated/kill-switch")
    public Map<String, Object> setKillSwitch(@RequestHeader("X-Tenant-Id") String tenant,
                                             @RequestBody Map<String, Object> body) {
        boolean active = Boolean.parseBoolean(required(body, "active"));
        boolean value = scenarios.setKillSwitch(tenant, active, required(body, "actor"));
        return Collections.singletonMap("active", value);
    }

    private static String scenario(HttpServletRequest request) {
        String uri = request.getRequestURI();
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
