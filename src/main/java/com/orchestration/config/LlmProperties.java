package com.orchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds {@code config/llm.yml}. All Anthropic client tunables live here so nothing is hardcoded.
 * {@link #models} maps short aliases (opus/sonnet/haiku) to concrete model ids, referenced by
 * {@code config/agents.yml}.
 */
@ConfigurationProperties("llm")
public record LlmProperties(Api api, Retry retry, PromptCache promptCache, Map<String, String> models) {

    public LlmProperties {
        models = models == null ? Map.of() : Map.copyOf(models);
    }

    public record Api(String baseUrl, String anthropicVersion, String apiKey, int timeoutSeconds) {
    }

    public record Retry(int maxRetries, long initialBackoffMillis, long maxBackoffMillis, double multiplier) {
    }

    public record PromptCache(boolean enabled) {
    }

    /** Resolve an alias (or pass through a literal model id). */
    public String resolveModel(String aliasOrId) {
        return models.getOrDefault(aliasOrId, aliasOrId);
    }
}
