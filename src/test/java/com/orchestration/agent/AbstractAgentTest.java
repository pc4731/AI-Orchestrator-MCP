package com.orchestration.agent;

import com.orchestration.budget.DefaultTokenBudgetManager;
import com.orchestration.budget.TokenBudgetManager;
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

class AbstractAgentTest {

    private static GenericLlmAgent agent(ScriptedLlmClient llm, TokenBudgetManager budget) {
        AgentSpec spec = new AgentSpec(AgentId.random(), AgentRole.BACKEND_ARCHITECT,
                Set.of(Capability.DESIGN_ARCHITECTURE), new ModelId("m"), "system", 1024, 0.2, true, 1);
        return new GenericLlmAgent(spec, llm, budget);
    }

    private static Task task() {
        return new Task(new TaskId("t1"), "Design API", "desc", AgentRole.BACKEND_ARCHITECT,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
    }

    private static Agent.Request request(Map<String, String> artifacts) {
        return new Agent.Request(task(), "do it", artifacts, Map.of());
    }

    private static Agent.Context context() {
        return new Agent.Context("p1", "c1", Map.of());
    }

    @Test
    void parsesValidStructuredResponse() {
        ScriptedLlmClient llm = new ScriptedLlmClient(
                "{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\",\"assumptions\":[\"a1\"],\"output\":{\"design\":\"x\"}}");

        Agent.Response response = agent(llm, new DefaultTokenBudgetManager()).handle(request(Map.of()), context());

        assertEquals(Agent.Outcome.COMPLETED, response.outcome());
        assertEquals(Agent.Confidence.HIGH, response.confidence());
        assertEquals(List.of("a1"), response.assumptions());
        assertEquals("x", response.structuredOutput().get("design"));
    }

    @Test
    void rePromptsOnSchemaViolationThenSucceeds() {
        ScriptedLlmClient llm = new ScriptedLlmClient(
                "this is not json",
                "{\"status\":\"COMPLETED\",\"confidence\":\"MEDIUM\"}");

        Agent.Response response = agent(llm, new DefaultTokenBudgetManager()).handle(request(Map.of()), context());

        assertEquals(Agent.Outcome.COMPLETED, response.outcome());
        assertEquals(2, llm.captured.size());
        assertTrue(llm.captured.get(1).messages().get(0).content().contains("previous reply was rejected"));
    }

    @Test
    void returnsInsufficientInformationAfterExhaustingRePrompts() {
        ScriptedLlmClient llm = new ScriptedLlmClient("nope", "still nope");

        Agent.Response response = agent(llm, new DefaultTokenBudgetManager()).handle(request(Map.of()), context());

        assertEquals(Agent.Outcome.INSUFFICIENT_INFORMATION, response.outcome());
        assertTrue(response.escalationReason().isPresent());
    }

    @Test
    void escalatesOnTokenBudgetBreach() {
        DefaultTokenBudgetManager budget = new DefaultTokenBudgetManager();
        budget.registerTaskBudget(new TaskId("t1"), 1); // each call uses 2 tokens total

        ScriptedLlmClient llm = new ScriptedLlmClient("{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\"}");
        Agent.Response response = agent(llm, budget).handle(request(Map.of()), context());

        assertEquals(Agent.Outcome.ESCALATE, response.outcome());
    }

    @Test
    void groundingArtifactsAreIncludedInThePrompt() {
        ScriptedLlmClient llm = new ScriptedLlmClient("{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\"}");

        agent(llm, new DefaultTokenBudgetManager()).handle(request(Map.of("spec.md", "IMPORTANT_GROUNDING")), context());

        String prompt = llm.captured.get(0).messages().get(0).content();
        assertTrue(prompt.contains("spec.md"));
        assertTrue(prompt.contains("IMPORTANT_GROUNDING"));
    }
}
