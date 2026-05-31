package com.orchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code skills} settings: where pluggable agent-skill files live. Each agent opts into
 * skills by name in {@code agents.yml}; the {@code SkillRegistry} loads them from this directory.
 */
@ConfigurationProperties("skills")
public record SkillsProperties(String dir) {

    public SkillsProperties {
        dir = dir == null || dir.isBlank() ? "./config/skills" : dir;
    }
}
