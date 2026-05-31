package com.orchestration.config;

import java.util.List;

/**
 * One agent's configuration from {@code config/agents.yml}: which model it uses (alias or id),
 * where its system prompt lives, its capabilities, generation limits, and any pluggable
 * {@code skills} to attach (resolved by the {@code SkillRegistry} and appended to its prompt).
 */
public record AgentDefinition(
        String role,
        String model,
        String promptFile,
        List<String> capabilities,
        Integer maxOutputTokens,
        Double temperature,
        List<String> skills
) {

    public AgentDefinition {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /** Factory for entries without skills (kept off the constructor set so Spring's
     *  {@code @ConfigurationProperties} record binding stays unambiguous). */
    public static AgentDefinition of(String role, String model, String promptFile,
                                     List<String> capabilities, Integer maxOutputTokens, Double temperature) {
        return new AgentDefinition(role, model, promptFile, capabilities, maxOutputTokens, temperature, List.of());
    }
}
