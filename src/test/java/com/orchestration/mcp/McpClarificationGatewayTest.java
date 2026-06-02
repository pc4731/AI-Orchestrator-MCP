package com.orchestration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.memory.SqliteMemoryStore;
import com.orchestration.web.ActiveProject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the pre-build clarification turn flows through the same park/poll/submit machinery as an
 * agent turn: the gateway parks a USER question on the bridge, {@code orchestrate_next} relays it to
 * Claude as an ASK_USER prompt (never to be answered as an agent), and {@code orchestrate_submit}
 * carries the human's words back to the planner.
 */
class McpClarificationGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void questionsAreRelayedToTheUserAndAnswersReturnToThePlanner() throws Exception {
        McpBridge bridge = new McpBridge();
        McpClarificationGateway gateway = new McpClarificationGateway(bridge);
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            OrchestrationMcpService service = new OrchestrationMcpService(
                    neverCalledEngine(), bridge, memory, new ActiveProject());

            // The planner's clarification turn blocks here until the human answers — run it async.
            CompletableFuture<Optional<String>> answer = CompletableFuture.supplyAsync(() ->
                    gateway.ask("p1", List.of("Which auth provider?"), "a todo app"));

            // Claude pulls the next thing to do and gets a question FOR THE USER, not an agent task.
            Map<String, Object> next = pollUntilUserQuery(service);
            assertEquals("ASK_USER", next.get("nextAction"));
            Map<?, ?> query = (Map<?, ?>) next.get("userQuery");
            assertEquals(Boolean.TRUE, query.get("forUser"));
            assertTrue(String.valueOf(query.get("questionsForUser")).contains("Which auth provider?"));

            // Claude relays it, gets the human's reply, and submits the user's own words.
            String taskId = String.valueOf(query.get("taskId"));
            service.submit(taskId, mapper.readTree("{\"output\":{\"answers\":\"Google OAuth\"}}"));

            assertEquals(Optional.of("Google OAuth"), answer.get(2, TimeUnit.SECONDS));
        } finally {
            memory.close();
        }
    }

    private Map<String, Object> pollUntilUserQuery(OrchestrationMcpService service) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> next = service.next();
            if (next.containsKey("userQuery")) {
                return next;
            }
        }
        throw new AssertionError("no userQuery was relayed");
    }

    /** The engine is never touched on this path (no active project); fail loudly if it ever is. */
    private static OrchestrationEngine neverCalledEngine() {
        return new OrchestrationEngine() {
            @Override public ProjectHandle submit(ProjectRequest request) { throw new UnsupportedOperationException(); }
            @Override public ProjectHandle resume(String projectId) { throw new UnsupportedOperationException(); }
            @Override public ProjectStatus status(String projectId) { throw new UnsupportedOperationException(); }
            @Override public void decideGate(String gateId, GateDecision decision) { throw new UnsupportedOperationException(); }
            @Override public void shutdown() { /* no-op */ }
        };
    }
}
