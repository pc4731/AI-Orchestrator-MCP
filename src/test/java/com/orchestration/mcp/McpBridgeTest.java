package com.orchestration.mcp;

import com.orchestration.agent.Agent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpBridgeTest {

    private static McpBridge.PendingTask task(String id, String projectId) {
        return new McpBridge.PendingTask(id, projectId, "BACKEND_DEVELOPER", "t", "d", "sys", "instr", "schema", Map.of());
    }

    private static Agent.Response completed() {
        return new Agent.Response(Agent.Outcome.COMPLETED, Map.of(), List.of(),
                Agent.Confidence.HIGH, List.of(), Optional.empty());
    }

    @Test
    void dispatchParksTaskAndBlocksUntilCompleted() throws Exception {
        McpBridge bridge = new McpBridge();
        CompletableFuture<Agent.Response> dispatched =
                CompletableFuture.supplyAsync(() -> bridge.dispatch(task("t1", "p1")));

        Optional<McpBridge.PendingTask> polled = bridge.poll(2000);
        assertTrue(polled.isPresent());
        assertEquals("t1", polled.get().taskId());
        assertFalse(dispatched.isDone());

        assertTrue(bridge.complete("t1", completed()));
        assertEquals(Agent.Outcome.COMPLETED, dispatched.get(2, TimeUnit.SECONDS).outcome());
    }

    @Test
    void completeUnknownTaskReturnsFalse() {
        assertFalse(new McpBridge().complete("nope", completed()));
    }

    @Test
    void startRendezvousResolvesWithFirstDispatchedProjectId() throws Exception {
        McpBridge bridge = new McpBridge();
        bridge.armStart();
        CompletableFuture.runAsync(() -> bridge.dispatch(task("t1", "proj-42")));
        assertEquals("proj-42", bridge.awaitProjectId(2000));
    }

    @Test
    void pollTimesOutWhenNoTask() {
        assertTrue(new McpBridge().poll(50).isEmpty());
    }
}
