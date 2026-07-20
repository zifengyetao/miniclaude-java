package com.miniclaude.domain.runtime;

/**
 * 模型调用 Outbound Port。
 * <p>
 * <b>为何放在 domain：</b>Runtime 需 LLM 能力但不依赖 OpenAI/Anthropic SDK。
 * <p>
 * <b>不变量：</b>实现须保留 request 中 tenant/trace 边界；调用通常计费、不幂等。
 * <p>
 * <b>边界：</b>infrastructure HTTP 客户端实现；与 {@link com.miniclaude.domain.agent.AgentSettings} 路由配合。
 */
public interface ModelGateway {

    /**
     * 完成一次模型补全（chat/completion）。
     *
     * @param request context + model + prompt
     * @return 文本与 token 统计
     */
    ModelResult complete(ModelRequest request);
}
