package com.miniclaude.domain.runtime;

/**
 * 模型调用出站端口。
 */
public interface ModelGateway {

    ModelResult complete(ModelRequest request);
}
