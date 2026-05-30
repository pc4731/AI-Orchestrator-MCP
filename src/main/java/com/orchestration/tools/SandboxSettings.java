package com.orchestration.tools;

import java.time.Duration;
import java.util.List;

/**
 * Sandbox configuration for {@link DockerToolExecutor}. Mirrors {@code config/sandbox.yml}; bound
 * from YAML in a later (configuration) step. Kept as a plain value type so the executor can be
 * constructed and unit-tested without Spring.
 *
 * @param allowedCommands the only program names an agent may invoke inside the sandbox.
 */
public record SandboxSettings(
        String image,
        String network,
        String cpuLimit,
        String memoryLimit,
        String workdir,
        boolean readOnlyRoot,
        Duration timeout,
        List<String> allowedCommands
) {

    public SandboxSettings {
        allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
    }
}
