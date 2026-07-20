package com.miniclaude.domain.runtime;

/**
 * 工具执行结果的最小领域表示。
 * <p>
 * <b>为何放在 domain：</b>统一工具循环消费的结果形状。
 * <p>
 * <b>不变量：</b>output 永非 null；successful 由适配器根据进程退出码/异常设置。
 * <p>
 * <b>边界：</b>不表达 stderr 分离、结构化 JSON；不承诺副作用可回滚。
 */
public final class ToolResult {

    /** 工具是否执行成功（业务/进程意义，非策略 ALLOW）。 */
    private final boolean successful;
    /**  stdout 或合并输出文本。 */
    private final String output;

    public ToolResult(boolean successful, String output) {
        this.successful = successful;
        this.output = output != null ? output : "";
    }

    /** @return 是否成功 */
    public boolean isSuccessful() {
        return successful;
    }

    /** @return 输出文本 */
    public String getOutput() {
        return output;
    }
}
