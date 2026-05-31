package com.orchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Filesystem locations and run settings for the orchestrator's working data (bound from
 * {@code application.yml}). {@code testCommand} is the project's default QA command — change it for
 * non-Gradle stacks (e.g. {@code [npm, test]} or {@code [pytest]}); the Team Lead can also set a
 * per-task override.
 */
@ConfigurationProperties("workspace")
public record WorkspaceProperties(String repoDir, List<String> testCommand) {

    public WorkspaceProperties {
        repoDir = repoDir == null ? "./data/repo" : repoDir;
        testCommand = testCommand == null || testCommand.isEmpty()
                ? List.of("./gradlew", "test") : List.copyOf(testCommand);
    }
}
