package com.miniclaude.domain.runtime;

import java.util.Objects;

/**
 * 模型调用的不可变请求 DTO。
 * <p>
 * <b>为何放在 domain：</b>统一向 ModelGateway 传参。
 * <p>
 * <b>不变量：</b>context 非 null；model/prompt 非空白（不负责 prompt  injection 防护）。
 * <p>
 * <b>边界：</b>application/runtime 组装；infrastructure 发起 HTTP。
 */
public final class ModelRequest {

    /** 执行边界。 */
    private final ExecutionContext context;
    /** 模型路由名。 */
    private final String model;
    /** 提示词/消息体（格式由 gateway 解释）。 */
    private final String prompt;

    public ModelRequest(ExecutionContext context, String model, String prompt) {
        this.context = Objects.requireNonNull(context, "context");
        this.model = requireText(model, "model");
        this.prompt = requireText(prompt, "prompt");
    }

    /** @return 上下文 */
    public ExecutionContext getContext() {
        return context;
    }

    /** @return 模型名 */
    public String getModel() {
        return model;
    }

    /** @return 提示词 */
    public String getPrompt() {
        return prompt;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
