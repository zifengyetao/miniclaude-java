package com.miniclaude.interfaces.rest;

import com.miniclaude.application.platform.AgentPlatformService;
import com.miniclaude.domain.platform.AgentDefinition;
import com.miniclaude.interfaces.rest.dto.CreateAgentDefinitionRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/platform/agents")
public class PlatformAgentController {

    private final AgentPlatformService platform;

    public PlatformAgentController(AgentPlatformService platform) {
        this.platform = platform;
    }

    @GetMapping
    public List<AgentDefinition> list() {
        return platform.listAgents();
    }

    @GetMapping("/{id}")
    public AgentDefinition get(@PathVariable String id) {
        return platform.getAgent(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentDefinition create(@Valid @RequestBody CreateAgentDefinitionRequest request) {
        return platform.createAgent(
                request.getName(),
                request.getDescription(),
                request.getRoleName(),
                request.getRiskLevel(),
                request.getEvolutionLevel(),
                request.getExecutionModes());
    }
}
