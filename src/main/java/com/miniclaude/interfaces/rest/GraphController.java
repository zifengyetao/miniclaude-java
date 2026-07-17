package com.miniclaude.interfaces.rest;

import com.miniclaude.domain.graph.GraphValidationResult;
import com.miniclaude.domain.graph.GraphValidator;
import com.miniclaude.interfaces.rest.dto.ValidateGraphRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/v1/platform/graphs")
public class GraphController {

    private final GraphValidator validator = new GraphValidator();

    @PostMapping("/validate")
    public GraphValidationResult validate(@Valid @RequestBody ValidateGraphRequest request) {
        return validator.validate(request.toGraphSpec());
    }
}
