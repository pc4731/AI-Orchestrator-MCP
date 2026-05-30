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

class TeamLeadAgentTest {

    @Test
    void decompositionPromptAsksForTasksAndOutputCarriesThem() {
        ScriptedLlmClient llm = new ScriptedLlmClient(
                "{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\",\"output\":{\"tasks\":["
                        + "{\"id\":\"t1\",\"title\":\"Design\",\"role\":\"BACKEND_ARCHITECT\",\"dependsOn\":[]}]}}");
        AgentSpec spec = new AgentSpec(AgentId.random(), AgentRole.TEAM_LEAD,
                Set.of(Capability.DECOMPOSE_TASKS), new ModelId("m"), "system", 4096, 0.2, true, 1);
        TeamLeadAgent agent = new TeamLeadAgent(spec, llm, new DefaultTokenBudgetManager());

        Task task = new Task(new TaskId("plan"), "Plan", "build a todo app", AgentRole.TEAM_LEAD,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        Agent.Response response = agent.handle(
                new Agent.Request(task, "build a todo app", Map.of(), Map.of()),
                new Agent.Context("p1", "c1", Map.of()));

        assertEquals(Agent.Outcome.COMPLETED, response.outcome());
        assertTrue(response.structuredOutput().containsKey("tasks"));
        assertTrue(llm.lastUserPrompt().toLowerCase().contains("decompose"));
    }
}
