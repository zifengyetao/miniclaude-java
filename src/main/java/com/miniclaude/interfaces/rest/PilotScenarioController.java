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
        status(tenant, scenario, runId);
        return scenarios.artifacts(tenant, runId);
    }
}
