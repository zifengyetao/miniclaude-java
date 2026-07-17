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

@Component
public class LocalWorkspaceSecurity implements SandboxPolicy, WorkspaceLease.Provider {
    private final List<Path> allowedRoots = new ArrayList<>();
    private final Set<Path> activeLeases = ConcurrentHashMap.newKeySet();

    public LocalWorkspaceSecurity(
            @Value("${platform.sandbox.allowed-workspaces:}") String configuredRoots) {
        for (String root : configuredRoots.split(",")) {
            if (!root.trim().isEmpty()) {
                allowedRoots.add(Paths.get(root.trim()).toAbsolutePath().normalize());
            }
        }
    }

    @Override
    public boolean allows(Path workspace) {
        if (workspace == null || allowedRoots.isEmpty()) return false;
        Path candidate = workspace.toAbsolutePath().normalize();
        return Files.isDirectory(candidate) && allowedRoots.stream().anyMatch(candidate::startsWith);
    }

    @Override
    public WorkspaceLease acquire(Path workspace, String runId) {
        if (!allows(workspace)) throw new IllegalStateException("workspace is not explicitly allowed");
        Path resolved = workspace.toAbsolutePath().normalize();
        if (!activeLeases.add(resolved)) {
            throw new IllegalStateException("workspace already has an active lease");
        }
        String leaseId = UUID.randomUUID().toString();
        return new WorkspaceLease() {
            private boolean closed;
            @Override public Path getWorkspace() {
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
