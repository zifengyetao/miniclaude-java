package com.miniclaude.interfaces.rest.dto;

/**
 * 创建会话请求体。
 */
public class CreateSessionRequest {

    /** 可选模型；未传时使用全局默认模型。 */
    private String model;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
