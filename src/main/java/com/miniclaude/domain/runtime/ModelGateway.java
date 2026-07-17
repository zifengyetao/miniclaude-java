package com.miniclaude.domain.runtime;

/**
 * 模型调用出站端口。
 *
 * <p>端口不绑定供应商 SDK。实现必须保留请求中的租户与追踪边界，并自行定义超时、
 * 重试和限流；模型调用通常计费且不保证幂等。
 */
public interface ModelGateway {

    ModelResult complete(ModelRequest request);
}
