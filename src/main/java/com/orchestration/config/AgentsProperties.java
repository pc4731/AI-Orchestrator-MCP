package com.orchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds {@code config/agents.yml} (the {@code agents.definitions} map). Keyed by a short agent name;
 * the value is that agent's {@link AgentDefinition}.
 */
@ConfigurationProperties("agents")
public record AgentsProperties(Map<String, AgentDefinition> definitions) {

    public AgentsProperties {
        definitions = definitions == null ? Map.of() : Map.copyOf(definitions);
    }
}
