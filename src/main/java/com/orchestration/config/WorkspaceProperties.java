package com.orchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Filesystem locations for the orchestrator's working data (bound from {@code application.yml}). */
@ConfigurationProperties("workspace")
public record WorkspaceProperties(String repoDir) {

    public WorkspaceProperties {
        repoDir = repoDir == null ? "./data/repo" : repoDir;
    }
}
