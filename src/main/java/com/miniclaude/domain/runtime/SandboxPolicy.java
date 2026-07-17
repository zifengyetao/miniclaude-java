package com.miniclaude.domain.runtime;

import java.nio.file.Path;

/** 工作区访问策略；无法明确允许时必须拒绝。 */
public interface SandboxPolicy {
    boolean allows(Path workspace);
}
