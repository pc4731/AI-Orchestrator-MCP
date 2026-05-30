package com.orchestration.llm;

import java.time.Duration;

/**
 * Plain configuration for {@link AnthropicLlmClient}. Kept in the {@code llm} package (rather than
 * depending on the Spring {@code @ConfigurationProperties} type) so the client stays free of any
 * framework coupling; the Spring configuration maps the bound properties into this record.
 */
public record AnthropicClientConfig(
        String baseUrl,
        String anthropicVersion,
        String apiKey,
        Duration timeout,
        int maxRetries,
        long initialBackoffMillis,
        long maxBackoffMillis,
        double multiplier,
        boolean promptCacheEnabled
) {

    public AnthropicClientConfig {
        baseUrl = baseUrl == null ? "https://api.anthropic.com" : baseUrl;
        anthropicVersion = anthropicVersion == null ? "2023-06-01" : anthropicVersion;
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        if (multiplier < 1.0) {
            multiplier = 2.0;
        }
    }
}
