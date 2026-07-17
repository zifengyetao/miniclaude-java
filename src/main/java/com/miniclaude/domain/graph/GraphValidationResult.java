package com.miniclaude.domain.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Graph Compiler 的静态校验结果。 */
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
