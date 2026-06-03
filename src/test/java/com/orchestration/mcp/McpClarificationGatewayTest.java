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
 * Proves a clarification turn reaches the human through BOTH channels: it surfaces in the web
 * dashboard via {@link QuestionController} ({@code /api/questions}) AND in the CLI via
 * {@code orchestrate_next} (ASK_USER) — and answering through either one carries the human's words
 * back to the planner.
 */
class McpClarificationGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void questionsSurfaceToTheUiAndAnswersReturnToThePlanner() throws Exception {
        McpBridge bridge = new McpBridge();
        McpClarificationGateway gateway = new McpClarificationGateway(bridge);
        QuestionController questions = new QuestionController(bridge);

        // The planner's clarification turn blocks until the human answers — run it async.
        CompletableFuture<Optional<String>> answer = CompletableFuture.supplyAsync(() ->
                gateway.ask("p1", List.of("Which auth provider?"), "a todo app"));

        // The question shows up for the human in the dashboard, not in the agent loop.
        Map<String, Object> q = pollUntilQuestion(questions);
        assertEquals("clarify", q.get("kind"));
        assertTrue(String.valueOf(q.get("question")).contains("Which auth provider?"));

        // The user answers it in the UI (by voice or text); the planner gets their words.
        questions.answer(String.valueOf(q.get("taskId")), new QuestionController.Answer("Google OAuth", true));

        assertEquals(Optional.of("Google OAuth"), answer.get(2, TimeUnit.SECONDS));
    }

    @Test
    void confirmationApprovalLetsThePlannerProceed() throws Exception {
        McpBridge bridge = new McpBridge();
        McpClarificationGateway gateway = new McpClarificationGateway(bridge);
        QuestionController questions = new QuestionController(bridge);

        CompletableFuture<com.orchestration.engine.ClarificationGateway.Confirmation> confirmation =
                CompletableFuture.supplyAsync(() -> gateway.confirm("p1", "Here is the plan…"));

        Map<String, Object> q = pollUntilQuestion(questions);
        assertEquals("confirm", q.get("kind"));

        // The user taps Approve in the UI.
        questions.answer(String.valueOf(q.get("taskId")), new QuestionController.Answer("", true));

        assertTrue(confirmation.get(2, TimeUnit.SECONDS).confirmed());
    }

    @Test
    void theSameQuestionCanAlsoBeAnsweredFromTheCli() throws Exception {
        McpBridge bridge = new McpBridge();
        McpClarificationGateway gateway = new McpClarificationGateway(bridge);
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            OrchestrationMcpService service = new OrchestrationMcpService(
                    neverCalledEngine(), bridge, memory, new ActiveProject());

            CompletableFuture<Optional<String>> answer = CompletableFuture.supplyAsync(() ->
                    gateway.ask("p1", List.of("Which database?"), "ctx"));

            // CLI: when no agent task is ready, orchestrate_next surfaces the question as ASK_USER.
            Map<String, Object> next = pollUntilAskUser(service);
            Map<?, ?> query = (Map<?, ?>) next.get("userQuery");
            assertTrue(String.valueOf(query.get("questionsForUser")).contains("Which database?"));

            // The user answers in the CLI chat; the planner gets it.
            service.submit(String.valueOf(query.get("taskId")),
                    mapper.readTree("{\"output\":{\"answers\":\"Postgres\"}}"));

            assertEquals(Optional.of("Postgres"), answer.get(8, TimeUnit.SECONDS));
        } finally {
            memory.close();
        }
    }

    private Map<String, Object> pollUntilQuestion(QuestionController questions) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> pending = questions.pending();
            if (!pending.isEmpty()) {
                return pending.get(0);
            }
        }
        throw new AssertionError("no question surfaced to the UI");
    }

    private Map<String, Object> pollUntilAskUser(OrchestrationMcpService service) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> next = service.next();
            if ("ASK_USER".equals(next.get("nextAction"))) {
                return next;
            }
        }
        throw new AssertionError("no ASK_USER surfaced to the CLI");
    }

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
