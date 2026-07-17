package com.miniclaude.domain.platform;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository {
    AgentRun save(AgentRun run);
    Optional<AgentRun> findById(String id);
    List<AgentRun> findAll();
}
