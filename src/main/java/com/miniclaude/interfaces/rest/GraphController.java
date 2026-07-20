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
 * Agent 图定义静态校验的 REST 入站适配器。
 *
 * <p><b>职责</b>：将 HTTP JSON 转为 {@link com.miniclaude.domain.graph.GraphSpec} 并返回
 * 纯静态诊断结果；不保存、不执行图，也不解析节点引用的外部资产。
 * <p><b>上游</b>：平台控制台或 CI 在发布前 POST 校验请求。
 * <b>下游</b>：无状态 {@link GraphValidator}，可在控制器实例上并发复用。
 * <p><b>安全/约束</b>：无副作用、幂等；相同请求体多次调用等价。校验失败不抛异常，
 * 而是返回 {@link GraphValidationResult} 中的错误列表，便于批量展示。
 */
@RestController
@RequestMapping("/api/v1/platform/graphs")
public class GraphController {

    /** 无状态校验器，构造一次即可服务所有请求 */
    private final GraphValidator validator = new GraphValidator();

    /**
     * 对图执行纯静态结构校验。
     *
     * @param request 经 {@code @Valid} 校验的图 JSON；{@link ValidateGraphRequest#toGraphSpec()} 转为领域对象
     * @return 校验结果（含 {@code valid} 标志与错误/警告列表）
     * @throws javax.validation.ValidationException DTO 层约束失败（400，由全局处理器处理）
     * @throws IllegalArgumentException {@code toGraphSpec()} 转换时领域参数非法（400）
     * @implNote 不持久化图定义；执行期校验由 {@code GraphRuntime} 与场景服务承担
     */
    @PostMapping("/validate")
    public GraphValidationResult validate(@Valid @RequestBody ValidateGraphRequest request) {
        return validator.validate(request.toGraphSpec());
    }
}
