package com.orchestration.agent;

import com.orchestration.task.Task;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The base abstraction for every specialised agent.
 *
 * <p>This is the seam that satisfies the Open/Closed principle: new agents are added by
 * implementing this interface and registering a role, without modifying the
 * {@code OrchestrationEngine}. Concrete agents are created by an {@code AgentFactory}
 * (Factory pattern) and invoked by the engine on virtual threads.
 *
 * <p><b>Contract for implementations:</b>
 * <ul>
 *   <li>Be stateless with respect to a single call — everything needed arrives via
 *       {@link Request}/{@link Context}; collaborators (LLM client, memory) are injected.</li>
 *   <li>Never throw for a <i>business</i> outcome. Insufficient grounding, the need to
 *       escalate, or a failed self-check are expressed through {@link Outcome}, not exceptions.
 *       Exceptions are reserved for genuine infrastructure faults.</li>
 *   <li>Ground every claim in {@link Request#inputArtifacts()} and report
 *       {@link #role()}-appropriate {@link Confidence} plus an explicit assumptions list
 *       (anti-hallucination requirements from the README).</li>
 * </ul>
 */
public interface Agent {

    AgentId id();

    AgentRole role();

    Set<Capability> capabilities();

    /** Cheap routing predicate the engine consults before dispatching a task to this agent. */
    boolean canHandle(Task task);

    /** Execute one unit of work and return a structured, schema-validated result. */
    Response handle(Request request, Context context);

    // ------------------------------------------------------------------------
    // Contract types (nested to keep the agent's I/O contract in one place).
    // ------------------------------------------------------------------------

    /**
     * Immutable invocation input. Grounding artifacts are passed explicitly so the agent has a
     * concrete, verifiable source for its output rather than relying on model memory.
     */
    record Request(
            Task task,
            String instructions,
            Map<String, String> inputArtifacts,
            Map<String, Object> parameters
    ) {}

    /** Per-call execution context. Carried explicitly (no static singletons) to keep agents testable. */
    record Context(
            String projectId,
            String correlationId,
            Map<String, Object> sharedContext
    ) {}

    /**
     * Structured output. Validated against the agent's JSON schema by the engine before acceptance;
     * a schema violation triggers a re-prompt (the README's "structured outputs only" rule).
     */
    record Response(
            Outcome outcome,
            Map<String, Object> structuredOutput,
            List<Artifact> artifacts,
            Confidence confidence,
            List<String> assumptions,
            Optional<String> escalationReason
    ) {}

    /** A produced artifact destined for the Git-backed artifact repository. */
    record Artifact(String path, String content, String mediaType) {}

    /** Business outcome of a single agent call. */
    enum Outcome {
        COMPLETED,
        NEEDS_REVIEW,
        ESCALATE,
        INSUFFICIENT_INFORMATION,
        FAILED
    }

    enum Confidence {
        LOW,
        MEDIUM,
        HIGH
    }
}
