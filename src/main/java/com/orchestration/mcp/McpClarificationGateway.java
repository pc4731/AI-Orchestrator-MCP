package com.orchestration.mcp;

import com.orchestration.agent.Agent;
import com.orchestration.engine.ClarificationGateway;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Backs the {@link ClarificationGateway} with Claude Code as the human relay. Each request is parked
 * on the {@link McpBridge} as a {@code USER}-audience task; {@code orchestrate_next} presents it to
 * Claude with instructions to ask the real user (not to answer it itself), and
 * {@code orchestrate_submit} delivers the human's reply back here — unblocking the planner so the
 * clarification loop can continue. No graph task, no API call: the human turn flows through the same
 * park/poll/submit machinery as every agent turn.
 */
public class McpClarificationGateway implements ClarificationGateway {

    private final McpBridge bridge;

    public McpClarificationGateway(McpBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public Optional<String> ask(String projectId, List<String> questions, String contextSummary) {
        if (questions == null || questions.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder body = new StringBuilder(
                "The team needs answers from the user before any building starts. Relay these "
                        + "questions to the user VERBATIM, wait for their reply, then submit it:\n");
        for (int i = 0; i < questions.size(); i++) {
            body.append(i + 1).append(". ").append(questions.get(i)).append('\n');
        }
        if (contextSummary != null && !contextSummary.isBlank()) {
            body.append("\nWhat we understand so far (do not invent beyond this): ").append(contextSummary);
        }
        Agent.Response r = dispatchToUser(projectId, "Clarify requirements with the user", body.toString(),
                "{\"output\":{\"answers\":\"the user's answers, in their own words\"}}");
        String answers = stringField(r, "answers");
        if (answers.isBlank()) {
            answers = r.escalationReason().orElse("");
        }
        return answers.isBlank() ? Optional.empty() : Optional.of(answers);
    }

    @Override
    public Confirmation confirm(String projectId, String understanding) {
        String body = "Show the user this understanding of what will be built and ask them to confirm "
                + "it or correct it. Submit confirmed=true if they approve as-is, or confirmed=false "
                + "with their corrections in the corrections field:\n\n" + understanding;
        Agent.Response r = dispatchToUser(projectId, "Confirm the plan with the user", body,
                "{\"output\":{\"confirmed\":true,\"corrections\":\"changes the user asked for, if any\"}}");
        Object confirmed = r.structuredOutput().get("confirmed");
        // Absent/unparseable confirmation defaults to approved, so a missing field never deadlocks.
        boolean ok = confirmed == null || Boolean.parseBoolean(confirmed.toString());
        return ok ? Confirmation.approved() : Confirmation.changesRequested(stringField(r, "corrections"));
    }

    private Agent.Response dispatchToUser(String projectId, String title, String body, String schema) {
        McpBridge.PendingTask task = new McpBridge.PendingTask(
                UUID.randomUUID().toString(), projectId, "USER", title, body, "", body, schema,
                Map.of(), McpBridge.Audience.USER);
        return bridge.dispatch(task);
    }

    private static String stringField(Agent.Response r, String key) {
        Object v = r.structuredOutput().get(key);
        return v == null ? "" : v.toString();
    }
}
