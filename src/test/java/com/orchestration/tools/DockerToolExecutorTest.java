package com.orchestration.tools;

import com.orchestration.agent.Capability;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerToolExecutorTest {

    private static SandboxSettings settings() {
        return new SandboxSettings("img:1", "none", "2", "2g", "/workspace", true,
                Duration.ofSeconds(60), List.of("gradle", "java"));
    }

    private static ToolExecutor.ExecutionRequest request(List<String> command, Set<Capability> caps) {
        return new ToolExecutor.ExecutionRequest("/tmp/work", command, Map.of(), caps, Duration.ofSeconds(5));
    }

    @Test
    void rejectsCallerWithoutExecuteToolsCapability() {
        DockerToolExecutor executor = new DockerToolExecutor(settings());
        assertThrows(SecurityException.class,
                () -> executor.execute(request(List.of("gradle", "build"), Set.of())));
    }

    @Test
    void rejectsCommandNotOnAllowList() {
        DockerToolExecutor executor = new DockerToolExecutor(settings());
        assertThrows(SecurityException.class,
                () -> executor.execute(request(List.of("rm", "-rf", "/"), Set.of(Capability.EXECUTE_TOOLS))));
    }

    @Test
    void buildsDockerCommandWithLimitsMountAndCommandLast() {
        DockerToolExecutor executor = new DockerToolExecutor(settings(), "docker");
        List<String> cmd = executor.buildDockerCommand(
                request(List.of("gradle", "test"), Set.of(Capability.EXECUTE_TOOLS)));

        assertEquals("docker", cmd.get(0));
        assertEquals("run", cmd.get(1));
        assertTrue(cmd.contains("--rm"));
        assertTrue(cmd.contains("--read-only"));
        assertTrue(cmd.contains("--network") && cmd.contains("none"));
        assertTrue(cmd.contains("--cpus") && cmd.contains("2"));
        assertTrue(cmd.contains("--memory") && cmd.contains("2g"));
        assertTrue(cmd.contains("/tmp/work:/workspace"));
        assertTrue(cmd.contains("img:1"));
        assertEquals(List.of("gradle", "test"), cmd.subList(cmd.size() - 2, cmd.size()));
    }
}
