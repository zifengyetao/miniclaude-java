package com.miniclaude.domain.runtime;

/**
 * 工具执行结果。
 */
public final class ToolResult {

    private final boolean successful;
    private final String output;

    public ToolResult(boolean successful, String output) {
        this.successful = successful;
        this.output = output != null ? output : "";
    }

    public boolean isSuccessful() {
        return successful;
    }

    public String getOutput() {
        return output;
    }
}
