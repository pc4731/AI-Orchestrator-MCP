package com.orchestration.config;

import java.util.List;

/**
 * One agent's configuration from {@code config/agents.yml}: which model it uses (alias or id),
 * where its system prompt lives, its capabilities, and generation limits. Consumed by the
 * {@code AgentFactory} in a later step.
 */
public record AgentDefinition(
        String role,
        String model,
        String promptFile,
        List<String> capabilities,
        Integer maxOutputTokens,
        Double temperature
) {

    public AgentDefinition {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
