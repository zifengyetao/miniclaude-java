package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.runtime.SandboxPolicy;
import com.miniclaude.domain.runtime.WorkspaceLease;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地 Coding 场景的工作区沙箱与独占租约提供者。
 *
 * <p><b>实现双端口</b>：{@link SandboxPolicy}（路径是否允许）+
 * {@link WorkspaceLease.Provider}（同一目录独占锁）。Coding Run 必须先 {@link #allows}
 * 再通过 {@link #acquire} 获取租约，才能安全读写文件。</p>
 *
 * <p><b>配置</b>：{@code platform.sandbox.allowed-workspaces} 逗号分隔绝对/相对路径，
 * 构造时规范化为绝对 Path 列表。空配置 = 拒绝一切工作区（fail-closed）。</p>
 *
 * <p><b>进程内局限</b>：{@code activeLeases} 不跨 JVM/容器；生产须叠加 OS 权限、
 * 只读挂载、网络策略。本类是<b>必要非充分</b>条件。</p>
 */
@Component
public class LocalWorkspaceSecurity implements SandboxPolicy, WorkspaceLease.Provider {
    /** 允许的工作区根目录列表（已 absolute + normalize） */
    private final List<Path> allowedRoots = new ArrayList<>();
    /** 当前被租约占用的规范化路径集合；同一路径同时只允许一个 Run */
    private final Set<Path> activeLeases = ConcurrentHashMap.newKeySet();

    /**
     * 从配置解析允许根目录。
     *
     * @param configuredRoots {@code platform.sandbox.allowed-workspaces}，逗号分隔
     */
    public LocalWorkspaceSecurity(
            @Value("${platform.sandbox.allowed-workspaces:}") String configuredRoots) {
        for (String root : configuredRoots.split(",")) {
            if (!root.trim().isEmpty()) {
                allowedRoots.add(Paths.get(root.trim()).toAbsolutePath().normalize());
            }
        }
    }

    /**
     * 判断工作区是否位于显式白名单且当前为目录。空值或空配置均拒绝；
     * 方法可并发调用，但结果会随文件系统状态变化。
     */
    @Override
    public boolean allows(Path workspace) {
        // why：未配置允许根或路径为空时 fail closed，不能把当前目录当作隐式默认值。
        if (workspace == null || allowedRoots.isEmpty()) return false;
        Path candidate = workspace.toAbsolutePath().normalize();
        return Files.isDirectory(candidate) && allowedRoots.stream().anyMatch(candidate::startsWith);
    }

    /**
     * 申请进程内独占租约。路径必须获准，冲突时抛出异常；申请非幂等，
     * 同一路径并发申请仅一个成功，返回句柄的关闭操作可重复调用。
     */
    @Override
    public WorkspaceLease acquire(Path workspace, String runId) {
        // why：租约前再次验证路径，阻止调用方绕过 SandboxPolicy 直接申请任意目录。
        if (!allows(workspace)) throw new IllegalStateException("workspace is not explicitly allowed");
        Path resolved = workspace.toAbsolutePath().normalize();
        if (!activeLeases.add(resolved)) {
            // why：两个 Coding 运行共享目录会令验证快照与最终补丁不一致，必须拒绝并发占用。
            throw new IllegalStateException("workspace already has an active lease");
        }
        String leaseId = UUID.randomUUID().toString();
        return new WorkspaceLease() {
            private boolean closed;
            @Override public Path getWorkspace() {
                // 关闭后的租约不再暴露路径，避免业务代码在释放隔离锁后继续使用工作区。
                if (closed) throw new IllegalStateException("workspace lease closed");
                return resolved;
            }
            @Override public String getLeaseId() { return leaseId; }
            @Override public void close() {
                if (!closed) {
                    closed = true;
                    activeLeases.remove(resolved);
                }
            }
        };
    }
}
