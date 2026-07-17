package com.miniclaude.interfaces.rest;

import com.miniclaude.domain.graph.GraphValidationResult;
import com.miniclaude.domain.graph.GraphValidator;
import com.miniclaude.interfaces.rest.dto.ValidateGraphRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 图定义静态校验的 REST 入站适配器。
 *
 * <p>控制器只把经过 Bean Validation 的请求转换为领域图并返回诊断，不保存或执行图，
 * 也不解释节点引用。校验器无状态，可由控制器实例并发复用。
 */
@RestController
@RequestMapping("/api/v1/platform/graphs")
public class GraphController {

    private final GraphValidator validator = new GraphValidator();

    /**
     * 对图执行纯静态校验。请求必须满足 DTO 基础约束；转换失败由统一异常处理处理。
     * 该操作无副作用且幂等，相同请求在并发调用下得到等价结果。
     */
    @PostMapping("/validate")
    public GraphValidationResult validate(@Valid @RequestBody ValidateGraphRequest request) {
        return validator.validate(request.toGraphSpec());
    }
}
