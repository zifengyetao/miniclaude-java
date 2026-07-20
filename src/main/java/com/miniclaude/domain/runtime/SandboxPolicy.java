package com.miniclaude.domain.runtime;

import java.nio.file.Path;

/**
 * 工作区访问策略 Outbound Port（路径授权白名单）。
 * <p>
 * <b>为何放在 domain：</b>声明「哪些 workspace 路径可被 Agent 使用」，与 OS ACL 互补。
 * <p>
 * <b>不变量：</b>空值、配置缺失、解析失败 → 返回 false（fail-closed）。
 * <p>
 * <b>边界：</b>infrastructure 读取 application.yml 白名单；实际 IO 时仍需 symlink 防护。
 */
public interface SandboxPolicy {

    /**
     * 判定工作区是否在允许范围内。
     *
     * @param workspace 待检查路径（通常已 normalize）
     * @return true 仅当明确授权
     */
    boolean allows(Path workspace);
}
