package com.orchestration.mcp;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentId;
import com.orchestration.agent.AgentRole;
import com.orchestration.task.Task;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAgentTest {

    /** Park a task for the given role and return the instructions handed to the brain. */
    private String instructionsFor(AgentRole role) {
        McpBridge bridge = new McpBridge();
        McpAgent agent = new McpAgent(AgentId.random(), role, Set.of(), "persona", bridge);
        Task task = new Task(new TaskId("t1"), "Task", "desc", role,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        CompletableFuture.runAsync(() -> agent.handle(
                new Agent.Request(task, "", Map.of(), Map.of()),
                new Agent.Context("p1", "t1", Map.of())));
        McpBridge.PendingTask parked = bridge.poll(2000).orElseThrow();
        bridge.complete("t1", new Agent.Response(Agent.Outcome.COMPLETED, Map.of(), List.of(),
                Agent.Confidence.HIGH, List.of(), Optional.empty()));
        return parked.instructions();
    }

    @Test
    void backendArchitectGetsArchitectureGuidance() {
        String i = instructionsFor(AgentRole.BACKEND_ARCHITECT);
        assertTrue(i.contains("backend architecture"));
        assertTrue(i.contains("output.instructions"));
    }

    @Test
    void uiDesignerGetsThemeAndTokenGuidance() {
        String i = instructionsFor(AgentRole.UI_DESIGNER);
        assertTrue(i.toLowerCase().contains("theme"));
        assertTrue(i.contains("output.tokens"));
    }

    @Test
    void dbaGetsSchemaGuidance() {
        String i = instructionsFor(AgentRole.DBA);
        assertTrue(i.contains("output.schema"));
    }

    @Test
    void securityReviewerGetsOwaspGuidance() {
        String i = instructionsFor(AgentRole.SECURITY_REVIEWER);
        assertTrue(i.contains("OWASP"));
        assertTrue(i.contains("output.findings"));
    }

    @Test
    void developerGetsArtifactsGuidance() {
        String i = instructionsFor(AgentRole.BACKEND_DEVELOPER);
        assertTrue(i.contains("artifacts"));
    }

    @Test
    void metersEstimatedUsageWhenABudgetIsWired() throws Exception {
        McpBridge bridge = new McpBridge();
        com.orchestration.budget.DefaultTokenBudgetManager budget =
                new com.orchestration.budget.DefaultTokenBudgetManager();
        McpAgent agent = new McpAgent(AgentId.random(), AgentRole.BACKEND_DEVELOPER, Set.of(),
                "persona", bridge, budget);
        Task task = new Task(new TaskId("t1"), "Task", "desc", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());

        CompletableFuture<Agent.Response> f = CompletableFuture.supplyAsync(() -> agent.handle(
                new Agent.Request(task, "build the thing",
                        Map.of("spec", "a fairly long specification grounding block"), Map.of()),
                new Agent.Context("p1", "t1", Map.of())));
        bridge.poll(2000).orElseThrow();
        bridge.complete("t1", new Agent.Response(Agent.Outcome.COMPLETED, Map.of("summary", "done"),
                List.of(new Agent.Artifact("src/A.java", "class A {}", "text/plain")),
                Agent.Confidence.HIGH, List.of(), Optional.empty()));
        f.get(2, java.util.concurrent.TimeUnit.SECONDS);

        assertTrue(budget.usedForProject("p1") > 0, "MCP turns must be metered (estimated)");
    }
}
