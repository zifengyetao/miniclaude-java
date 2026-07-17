package com.miniclaude.domain.platform;

import java.util.List;
import java.util.Optional;

public interface AgentDefinitionRepository {
    AgentDefinition save(AgentDefinition definition);
    Optional<AgentDefinition> findById(String id);
    List<AgentDefinition> findAll();
}
