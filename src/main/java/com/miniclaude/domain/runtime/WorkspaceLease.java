package com.miniclaude.domain.runtime;

import java.nio.file.Path;

public interface WorkspaceLease extends AutoCloseable {
    Path getWorkspace();
    String getLeaseId();
    @Override void close();

    interface Provider {
        WorkspaceLease acquire(Path workspace, String runId);
    }
}
