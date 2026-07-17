package com.miniclaude.domain.runtime;

import java.nio.file.Path;

/**
 * 工作区访问策略端口。
 *
 * <p>实现只在路径被明确授权时返回 {@code true}，空值、配置缺失、解析失败等不确定状态
 * 均应拒绝。该判定不能替代实际文件访问时的操作系统权限和符号链接防护。
 */
public interface SandboxPolicy {
    boolean allows(Path workspace);
}
