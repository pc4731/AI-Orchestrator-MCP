package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentId;
import com.orchestration.agent.AgentRole;
import com.orchestration.agent.Capability;
import com.orchestration.task.Task;
import com.orchestration.task.TaskGraph;
import com.orchestration.task.WorkflowState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TeamLeadProjectPlannerTest {

    private static AgentFactory teamLeadReturning(Agent.Response response) {
        Agent teamLead = new Agent() {
            @Override public AgentId id() { return new AgentId("tl"); }
            @Override public AgentRole role() { return AgentRole.TEAM_LEAD; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DECOMPOSE_TASKS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) { return response; }
        };
        return new AgentFactory() {
            @Override public Agent create(AgentRole role) { return teamLead; }
            @Override public boolean supports(AgentRole role) { return role == AgentRole.TEAM_LEAD; }
            @Override public Set<AgentRole> supportedRoles() { return Set.of(AgentRole.TEAM_LEAD); }
        };
    }

    private static OrchestrationEngine.ProjectRequest request() {
        return new OrchestrationEngine.ProjectRequest("build a todo app", Map.of(), Optional.empty());
    }

    @Test
    void buildsGraphFromDecompositionWithDependencies() {
        Map<String, Object> t1 = Map.of("id", "t1", "title", "Design", "role", "BACKEND_ARCHITECT",
                "dependsOn", List.of());
        Map<String, Object> t2 = Map.of("id", "t2", "title", "Build", "role", "BACKEND_DEVELOPER",
                "dependsOn", List.of("t1"));
        Agent.Response response = new Agent.Response(Agent.Outcome.COMPLETED,
                Map.of("tasks", List.of(t1, t2)), List.of(), Agent.Confidence.HIGH, List.of(), Optional.empty());

        TaskGraph graph = new TeamLeadProjectPlanner(teamLeadReturning(response)).plan("p1", request());

        assertEquals(2, graph.tasks().size());
        Task developerTask = graph.tasks().stream()
                .filter(t -> t.assignedRole() == AgentRole.BACKEND_DEVELOPER)
                .findFirst().orElseThrow();
        assertEquals(1, graph.dependencies(developerTask.id()).size());
    }

    @Test
    void fallsBackToASingleTeamLeadTaskWhenNoTasksReturned() {
        Agent.Response response = new Agent.Response(Agent.Outcome.INSUFFICIENT_INFORMATION,
                Map.of(), List.of(), Agent.Confidence.LOW, List.of(), Optional.of("too vague"));

        TaskGraph graph = new TeamLeadProjectPlanner(teamLeadReturning(response)).plan("p1", request());

        assertEquals(1, graph.tasks().size());
        assertEquals(AgentRole.TEAM_LEAD, graph.tasks().iterator().next().assignedRole());
        assertEquals(WorkflowState.PENDING, graph.tasks().iterator().next().state());
    }
}
