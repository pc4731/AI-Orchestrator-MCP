package com.orchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the committed project-knowledge file (bound from {@code workspace.knowledge}).
 *
 * <p>The file is plaintext Markdown committed alongside the code — it summarises source that ships in
 * the same repo, so there is nothing to hide. When {@code enabled} is false the feature is inert:
 * nothing is read or written and the team works from the code as before.
 */
@ConfigurationProperties("workspace.knowledge")
public record KnowledgeProperties(Boolean enabled, String path) {

    public KnowledgeProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        path = path == null || path.isBlank() ? ".project/knowledge.md" : path;
    }

    /** True when the feature is on. */
    public boolean active() {
        return Boolean.TRUE.equals(enabled);
    }
}
