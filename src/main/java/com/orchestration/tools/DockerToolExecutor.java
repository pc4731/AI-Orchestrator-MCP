package com.orchestration.tools;

import com.orchestration.agent.Capability;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link ToolExecutor} that runs every command inside a Docker container, never against the host.
 *
 * <p>Two layers of scoping before anything runs: the calling agent must hold the
 * {@link Capability#EXECUTE_TOOLS} capability, and the program being run must be on the configured
 * {@link SandboxSettings#allowedCommands()} allow-list. The container is started with the resource,
 * network and filesystem limits from {@link SandboxSettings}, and the agent's working directory is
 * bind-mounted into the sandbox workspace.
 */
public class DockerToolExecutor implements ToolExecutor {

    private final SandboxSettings settings;
    private final String dockerBinary;

    public DockerToolExecutor(SandboxSettings settings) {
        this(settings, "docker");
    }

    public DockerToolExecutor(SandboxSettings settings, String dockerBinary) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.dockerBinary = Objects.requireNonNull(dockerBinary, "dockerBinary");
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.grantedCapabilities().contains(Capability.EXECUTE_TOOLS)) {
            throw new SecurityException("Agent lacks the EXECUTE_TOOLS capability");
        }
        if (request.command().isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        validateCommand(request.command());

        List<String> docker = buildDockerCommand(request);
        Duration timeout = request.timeout() != null ? request.timeout() : settings.timeout();
        Instant start = Instant.now();
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(docker);
            request.environment().forEach(pb.environment()::put);
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start sandbox process", e);
        }

        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ExecutionResult(-1, stdout.join(), stderr.join(), true,
                        Duration.between(start, Instant.now()));
            }
            return new ExecutionResult(process.exitValue(), stdout.join(), stderr.join(), false,
                    Duration.between(start, Instant.now()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Interrupted while waiting for sandbox process", e);
        }
    }

    /** Assemble the {@code docker run ...} argv. Package-visible so it can be unit-tested directly. */
    List<String> buildDockerCommand(ExecutionRequest request) {
        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBinary);
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--network");
        cmd.add(settings.network());
        cmd.add("--cpus");
        cmd.add(settings.cpuLimit());
        cmd.add("--memory");
        cmd.add(settings.memoryLimit());
        if (settings.readOnlyRoot()) {
            cmd.add("--read-only");
        }
        cmd.add("-v");
        cmd.add(request.workingDirectory() + ":" + settings.workdir());
        cmd.add("-w");
        cmd.add(settings.workdir());
        cmd.add(settings.image());
        cmd.addAll(request.command());
        return cmd;
    }

    private void validateCommand(List<String> command) {
        String program = command.get(0);
        if (!settings.allowedCommands().contains(program)) {
            throw new SecurityException("Command not permitted in sandbox: " + program
                    + " (allowed: " + settings.allowedCommands() + ")");
        }
    }

    private static CompletableFuture<String> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
