package com.orchestration.config;

import com.orchestration.tools.SandboxSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Binds {@code config/sandbox.yml} and maps to a {@link SandboxSettings} for the tool executor. */
@ConfigurationProperties("sandbox")
public record SandboxProperties(boolean enabled, String type, Docker docker, List<String> allowedCommands) {

    public SandboxProperties {
        allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
    }

    public record Docker(
            String image,
            String network,
            String cpuLimit,
            String memoryLimit,
            String workdir,
            boolean readOnlyRoot,
            int timeoutSeconds
    ) {
    }

    public SandboxSettings toSettings() {
        return new SandboxSettings(
                docker.image(),
                docker.network(),
                docker.cpuLimit(),
                docker.memoryLimit(),
                docker.workdir(),
                docker.readOnlyRoot(),
                Duration.ofSeconds(docker.timeoutSeconds()),
                allowedCommands);
    }
}
