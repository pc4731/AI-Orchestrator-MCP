package com.orchestration.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackDetectorTest {

    @TempDir
    Path dir;

    @Test
    void pythonProjectYieldsPytest() throws IOException {
        Files.writeString(dir.resolve("pyproject.toml"), "[project]\nname = \"app\"\n");

        assertEquals(Optional.of(List.of("python3", "-m", "pytest")), StackDetector.testCommand(dir));
    }

    @Test
    void gradleWrapperIsPreferredOverBareGradle() throws IOException {
        Files.writeString(dir.resolve("gradlew"), "#!/bin/sh\n");
        Files.writeString(dir.resolve("build.gradle"), "plugins {}\n");

        assertEquals(Optional.of(List.of("./gradlew", "test")), StackDetector.testCommand(dir));
    }

    @Test
    void packageJsonCountsOnlyWithARealTestScript() throws IOException {
        Files.writeString(dir.resolve("package.json"),
                "{\"scripts\":{\"test\":\"echo \\\"Error: no test specified\\\" && exit 1\"}}");
        assertEquals(Optional.empty(), StackDetector.testCommand(dir), "npm's default stub is not a test");

        Files.writeString(dir.resolve("package.json"), "{\"scripts\":{\"test\":\"vitest run\"}}");
        assertEquals(Optional.of(List.of("npm", "test")), StackDetector.testCommand(dir));
    }

    @Test
    void mixedBackendFrontendComposesOneCommandCoveringBothStacks() throws IOException {
        Files.createDirectories(dir.resolve("backend"));
        Files.createDirectories(dir.resolve("frontend"));
        Files.writeString(dir.resolve("backend/requirements.txt"), "fastapi\n");
        Files.writeString(dir.resolve("frontend/package.json"), "{\"scripts\":{\"test\":\"vitest run\"}}");

        List<String> command = StackDetector.testCommand(dir).orElseThrow();
        assertEquals("bash", command.get(0));
        String script = command.get(2);
        assertTrue(script.contains("cd backend && python3 -m pytest"), script);
        assertTrue(script.contains("cd frontend && npm test"), script);
        assertTrue(script.contains(" && "), "both stacks must gate together: " + script);
    }

    @Test
    void noiseDirectoriesAreNeverScanned() throws IOException {
        Files.createDirectories(dir.resolve("node_modules"));
        Files.writeString(dir.resolve("node_modules/package.json"),
                "{\"scripts\":{\"test\":\"jest\"}}");

        assertEquals(Optional.empty(), StackDetector.testCommand(dir));
    }

    @Test
    void emptyWorkspaceDetectsNothing() {
        assertEquals(Optional.empty(), StackDetector.testCommand(dir),
                "a fresh workspace falls back to the configured default");
    }
}
