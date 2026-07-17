package com.miniclaude.domain.runtime;

/**
 * 工具执行结果的最小领域表示。
 *
 * <p>成功标志由适配器给出，输出缺失时规范化为空字符串；本类不解释输出内容，
 * 也不代表工具副作用可回滚。
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
