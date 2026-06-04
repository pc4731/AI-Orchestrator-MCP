package com.orchestration.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostProjectBuildVerifierTest {

    private final HostProjectBuildVerifier verifier = new HostProjectBuildVerifier();

    @Test
    void aZeroExitIsAPass(@TempDir Path dir) {
        ProjectBuildVerifier.Result r = verifier.verify(dir.toString(),
                List.of("sh", "-c", "echo building; exit 0"), Duration.ofSeconds(30));

        assertTrue(r.success());
        assertEquals(0, r.exitCode());
        assertFalse(r.couldNotStart());
        assertTrue(r.output().contains("building"));
    }

    @Test
    void aNonZeroExitIsAFailWithTheRealCode(@TempDir Path dir) {
        ProjectBuildVerifier.Result r = verifier.verify(dir.toString(),
                List.of("sh", "-c", "echo boom 1>&2; exit 7"), Duration.ofSeconds(30));

        assertFalse(r.success());
        assertEquals(7, r.exitCode());
        assertFalse(r.couldNotStart());
    }

    @Test
    void aMissingToolchainSignalsCouldNotStartRatherThanFailing(@TempDir Path dir) {
        ProjectBuildVerifier.Result r = verifier.verify(dir.toString(),
                List.of("definitely-not-a-real-binary-xyz", "test"), Duration.ofSeconds(30));

        assertFalse(r.success());
        assertTrue(r.couldNotStart());
    }

    @Test
    void anEmptyCommandCannotRun(@TempDir Path dir) {
        ProjectBuildVerifier.Result r = verifier.verify(dir.toString(), List.of(), Duration.ofSeconds(30));

        assertFalse(r.success());
        assertTrue(r.couldNotStart());
    }
}
