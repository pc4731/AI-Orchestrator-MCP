package com.orchestration.mcp;

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
 * Proves the pre-build clarification turn flows to the human via the web dashboard: the gateway parks
 * a USER question on the bridge, it surfaces through {@link QuestionController} ({@code /api/questions})
 * — NOT the agent loop — and answering it there carries the human's words back to the planner.
 */
class McpClarificationGatewayTest {

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
}
