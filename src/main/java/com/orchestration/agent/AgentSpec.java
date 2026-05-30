package com.orchestration.agent;

import com.orchestration.llm.ModelId;

import java.util.Set;

/**
 * Immutable construction parameters for an {@link AbstractAgent}. Bundling these keeps agent
 * constructors small and lets the {@code AgentFactory} assemble everything from YAML config.
 *
 * @param maxSchemaRetries how many times to re-prompt when the model returns output that does not
 *                         match the required JSON schema (anti-hallucination: reject & re-prompt).
 */
public record AgentSpec(
        AgentId id,
        AgentRole role,
        Set<Capability> capabilities,
        ModelId model,
        String systemPrompt,
        Integer maxTokens,
        Double temperature,
        boolean cachePrompt,
        int maxSchemaRetries
) {

    public AgentSpec {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }
}
