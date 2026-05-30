package com.orchestration.agent;

import com.orchestration.task.Task;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;
import com.orchestration.tools.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaEngineerAgentTest {

    private static QaEngineerAgent qa(ToolExecutor executor) {
        return new QaEngineerAgent(AgentId.random(), AgentRole.QA_ENGINEER,
                Set.of(Capability.RUN_TESTS, Capability.EXECUTE_TOOLS),
                executor, List.of("./gradlew", "test"), Duration.ofSeconds(5));
    }

    private static Agent.Request request() {
        Task task = new Task(new TaskId("t1"), "QA", "test it", AgentRole.QA_ENGINEER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        return new Agent.Request(task, "", Map.of(), Map.of("workingDir", "/tmp/work"));
    }

    @Test
    void reportsCompletedWhenTestsPass() {
        ToolExecutor executor = req -> new ToolExecutor.ExecutionResult(0, "BUILD SUCCESSFUL", "", false, Duration.ZERO);

        Agent.Response response = qa(executor).handle(request(), new Agent.Context("p1", "c1", Map.of()));

        assertEquals(Agent.Outcome.COMPLETED, response.outcome());
        assertEquals(0, response.structuredOutput().get("exitCode"));
    }

    @Test
    void reportsNeedsReviewWithDetailsWhenTestsFail() {
        ToolExecutor executor = req -> new ToolExecutor.ExecutionResult(1, "", "AssertionError", false, Duration.ZERO);

        Agent.Response response = qa(executor).handle(request(), new Agent.Context("p1", "c1", Map.of()));

        assertEquals(Agent.Outcome.NEEDS_REVIEW, response.outcome());
        assertTrue(response.escalationReason().isPresent());
        assertEquals("AssertionError", response.structuredOutput().get("stderr"));
    }

    @Test
    void passesWorkingDirectoryAndCapabilitiesToTheSandbox() {
        AtomicReference<ToolExecutor.ExecutionRequest> seen = new AtomicReference<>();
        ToolExecutor executor = req -> {
            seen.set(req);
            return new ToolExecutor.ExecutionResult(0, "", "", false, Duration.ZERO);
        };

        qa(executor).handle(request(), new Agent.Context("p1", "c1", Map.of()));

        assertEquals("/tmp/work", seen.get().workingDirectory());
        assertEquals(List.of("./gradlew", "test"), seen.get().command());
        assertTrue(seen.get().grantedCapabilities().contains(Capability.EXECUTE_TOOLS));
    }
}
