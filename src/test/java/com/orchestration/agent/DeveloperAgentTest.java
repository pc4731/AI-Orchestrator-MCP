package com.orchestration.agent;

import com.orchestration.budget.DefaultTokenBudgetManager;
import com.orchestration.llm.ModelId;
import com.orchestration.task.Task;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;
import com.orchestration.testsupport.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperAgentTest {

    private static DeveloperAgent developer(ScriptedLlmClient llm) {
        AgentSpec spec = new AgentSpec(AgentId.random(), AgentRole.BACKEND_DEVELOPER,
                Set.of(Capability.WRITE_CODE), new ModelId("m"), "system", 8192, 0.1, true, 1);
        return new DeveloperAgent(spec, llm, new DefaultTokenBudgetManager());
    }

    private static Agent.Request request() {
        Task task = new Task(new TaskId("t1"), "Implement", "code it", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        return new Agent.Request(task, "implement", Map.of(), Map.of());
    }

    @Test
    void keepsCompletedWhenCodeAndTestsAreProduced() {
        ScriptedLlmClient llm = new ScriptedLlmClient(
                "{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\",\"artifacts\":["
                        + "{\"path\":\"src/main/java/A.java\",\"content\":\"class A {}\"},"
                        + "{\"path\":\"src/test/java/ATest.java\",\"content\":\"class ATest {}\"}]}");

        Agent.Response response = developer(llm).handle(request(), new Agent.Context("p1", "c1", Map.of()));

        assertEquals(Agent.Outcome.COMPLETED, response.outcome());
        assertEquals(2, response.artifacts().size());
    }

    @Test
    void downgradesToReviewWhenCodeHasNoTests() {
        ScriptedLlmClient llm = new ScriptedLlmClient(
                "{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\",\"artifacts\":["
                        + "{\"path\":\"src/main/java/A.java\",\"content\":\"class A {}\"}]}");

        Agent.Response response = developer(llm).handle(request(), new Agent.Context("p1", "c1", Map.of()));

        assertEquals(Agent.Outcome.NEEDS_REVIEW, response.outcome());
        assertTrue(response.escalationReason().orElse("").toLowerCase().contains("test"));
    }

    @Test
    void downgradesToReviewWhenCompletedButNoCodeProduced() {
        ScriptedLlmClient llm = new ScriptedLlmClient(
                "{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\",\"output\":{\"summary\":\"all done\"}}");

        Agent.Response response = developer(llm).handle(request(), new Agent.Context("p1", "c1", Map.of()));

        assertEquals(Agent.Outcome.NEEDS_REVIEW, response.outcome());
        assertTrue(response.escalationReason().isPresent());
    }
}
