package com.orchestration.verify;

import java.time.Duration;
import java.util.List;

/**
 * Actually runs a project's build/test command and reports the real result — so "DONE" can mean the
 * generated code provably compiles and passes, not merely that an agent claimed it does. Used in the
 * mcp profile, where every role (including QA) is role-played and nothing would otherwise execute.
 */
public interface ProjectBuildVerifier {

    /**
     * @param success         true only when the command ran and exited 0
     * @param couldNotStart   the command could not be launched at all (e.g. toolchain not installed) —
     *                        distinct from "ran and failed", so the caller can degrade gracefully
     *                        instead of falsely blocking a project on an environment issue
     */
    record Result(boolean success, boolean couldNotStart, int exitCode, boolean timedOut, String output) {}

    /** Run {@code command} in {@code projectDir}, capped at {@code timeout}. Never throws. */
    Result verify(String projectDir, List<String> command, Duration timeout);
}
