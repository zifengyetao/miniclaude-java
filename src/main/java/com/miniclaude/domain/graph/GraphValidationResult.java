package com.miniclaude.domain.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Graph Compiler 的不可变静态校验结果。
 * <p>
 * <b>为何放在 domain：</b>校验结论（可执行/不可执行）是图领域的一部分，与 HTTP 状态码解耦。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@link #isValid()} 当且仅当 errors 为空。</li>
 *   <li>errors/warnings 列表构造后不可变。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application GraphController 将 errors 映射为 400；warnings 可返回 200 带告警体。
 */
public final class GraphValidationResult {

    /** 阻塞发布的结构/语义错误（图不可执行）。 */
    private final List<String> errors;
    /** 非阻塞告警（图可执行但配置可能不合理）。 */
    private final List<String> warnings;

    /**
     * @param errors   错误消息列表，null 按空处理
     * @param warnings 告警消息列表，null 按空处理
     */
    public GraphValidationResult(List<String> errors, List<String> warnings) {
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    /**
     * @return {@code true} 当无 errors（warnings 不影响 valid）
     */
    public boolean isValid() { return errors.isEmpty(); }

    /** @return 不可变错误列表 */
    public List<String> getErrors() { return errors; }

    /** @return 不可变告警列表 */
    public List<String> getWarnings() { return warnings; }
}
