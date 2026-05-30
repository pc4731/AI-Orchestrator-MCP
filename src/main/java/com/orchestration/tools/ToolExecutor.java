package com.orchestration.tools;

import com.orchestration.agent.Capability;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes real developer/QA tools (compile, build, run, test) inside a sandbox.
 *
 * <p>This is what makes the system verifiable rather than plausible-sounding: instead of trusting
 * an agent's claim that code compiles, the engine actually runs it. The v1 implementation runs
 * each command in a Docker container (see {@code config/sandbox.yml}); <b>no command is ever run
 * directly against the host</b>.
 *
 * <p>Execution is scoped per agent: an agent must hold the relevant {@link Capability}
 * (e.g. {@link Capability#EXECUTE_TOOLS}) for the call to be permitted.
 */
public interface ToolExecutor {

    /**
     * Run a command in the sandbox.
     *
     * @throws SecurityException if the granted capabilities or the command are not permitted
     */
    ExecutionResult execute(ExecutionRequest request);

    record ExecutionRequest(
            String workingDirectory,          // path mounted into the sandbox workspace
            List<String> command,             // argv form (no shell interpolation)
            Map<String, String> environment,
            Set<Capability> grantedCapabilities,
            Duration timeout
    ) {
        public ExecutionRequest {
            command = command == null ? List.of() : List.copyOf(command);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            grantedCapabilities = grantedCapabilities == null ? Set.of() : Set.copyOf(grantedCapabilities);
        }
    }

    record ExecutionResult(
            int exitCode,
            String stdout,
            String stderr,
            boolean timedOut,
            Duration duration
    ) {
        public boolean success() {
            return exitCode == 0 && !timedOut;
        }
    }
}
