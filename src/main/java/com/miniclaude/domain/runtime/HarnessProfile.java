package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 共享 Agent Harness 的不可变角色能力配置。
 *
 * <p>Profile 只声明指令、工具能力和预算，不实现独立 Loop。生产运行应绑定精确版本资产，
 * 禁止在一次 Run 中热替换。</p>
 */
public final class HarnessProfile {
    public enum AutonomyMode {
        /** 只生成建议，不自动执行工具。 */
        ADVISORY,
        /** 允许自动执行经过策略放行的只读工具。 */
        READ_ONLY_AUTO,
        /** 可执行受控工具；写操作仍由 Policy/HITL 决定。 */
        CONTROLLED
    }

    private final String id;
    private final String version;
    private final String systemInstruction;
    private final Set<String> allowedTools;
    private final AutonomyMode autonomyMode;
    private final int maxTurns;
    private final int maxToolCalls;
    private final int maxContextChars;

    public HarnessProfile(String id, String version, String systemInstruction,
                          Set<String> allowedTools, AutonomyMode autonomyMode,
                          int maxTurns, int maxToolCalls, int maxContextChars) {
        this.id = text(id, "id");
        this.version = text(version, "version");
        this.systemInstruction = text(systemInstruction, "systemInstruction");
        this.allowedTools = Collections.unmodifiableSet(new LinkedHashSet<>(
                allowedTools == null ? Collections.emptySet() : allowedTools));
        this.autonomyMode = autonomyMode == null ? AutonomyMode.ADVISORY : autonomyMode;
        if (maxTurns < 1 || maxToolCalls < 0 || maxContextChars < 512) {
            throw new IllegalArgumentException("invalid harness profile limits");
        }
        this.maxTurns = maxTurns;
        this.maxToolCalls = maxToolCalls;
        this.maxContextChars = maxContextChars;
    }

    public String getId() { return id; }
    public String getVersion() { return version; }
    public String getSystemInstruction() { return systemInstruction; }
    public Set<String> getAllowedTools() { return allowedTools; }
    public AutonomyMode getAutonomyMode() { return autonomyMode; }
    public int getMaxTurns() { return maxTurns; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public int getMaxContextChars() { return maxContextChars; }

    private static String text(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
