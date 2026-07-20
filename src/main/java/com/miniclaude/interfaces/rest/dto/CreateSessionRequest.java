package com.miniclaude.interfaces.rest.dto;

/**
 * 创建会话 POST 请求体 DTO。
 * <p>
 * 请求体完全可选；{@code SessionController#create} 接受 {@code null} 体。
 */
public class CreateSessionRequest {

    /** 可选模型 ID；未传时使用 {@code AgentSettings} 全局默认模型。 */
    private String model;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
