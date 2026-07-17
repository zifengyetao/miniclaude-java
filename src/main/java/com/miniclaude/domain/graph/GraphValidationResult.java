package com.miniclaude.domain.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Graph Compiler 的不可变静态校验结果。
 *
 * <p>错误表示图不可执行，告警表示可执行但配置可能不合理。本类只封装诊断信息，
 * 不负责把诊断映射为 HTTP 状态或异常；构造时复制列表以便安全共享。
 */
public final class GraphValidationResult {

    private final List<String> errors;
    private final List<String> warnings;

    public GraphValidationResult(List<String> errors, List<String> warnings) {
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public boolean isValid() { return errors.isEmpty(); }
    public List<String> getErrors() { return errors; }
    public List<String> getWarnings() { return warnings; }
}
