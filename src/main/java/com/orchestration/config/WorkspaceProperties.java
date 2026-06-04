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
public record WorkspaceProperties(String repoDir, String baseDir, List<String> testCommand,
                                  Boolean verifyBuild) {

    public WorkspaceProperties {
        repoDir = repoDir == null ? "./data/repo" : repoDir;
        // Parent directory under which each project gets its own folder (mcp profile). Defaults to the
        // user's Desktop so generated projects land somewhere a human can immediately find and open.
        baseDir = (baseDir == null || baseDir.isBlank())
                ? System.getProperty("user.home") + "/Desktop" : baseDir;
        testCommand = testCommand == null || testCommand.isEmpty()
                ? List.of("./gradlew", "test") : List.copyOf(testCommand);
        // mcp profile: actually run the test command against the project folder and gate DONE on the
        // real exit code, rather than trusting the role-played QA agent. On by default.
        verifyBuild = verifyBuild == null ? Boolean.TRUE : verifyBuild;
    }

    /** True when QA should run the real build and gate on its actual result. */
    public boolean verifyBuildEnabled() {
        return Boolean.TRUE.equals(verifyBuild);
    }
}
