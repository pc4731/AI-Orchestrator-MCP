package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentRole;
import com.orchestration.task.InMemoryTaskGraph;
import com.orchestration.task.Task;
import com.orchestration.task.TaskGraph;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The real {@link ProjectPlanner}: asks the Team Lead agent to decompose the feature request and
 * turns its {@code output.tasks} into a {@link TaskGraph}.
 *
 * <p>If the Team Lead cannot decompose (too ambiguous, or returns no tasks), the planner produces a
 * single Team Lead task. Running it lets the agent escalate through the normal human-in-the-loop
 * gate rather than the planner silently inventing work.
 */
public class TeamLeadProjectPlanner implements ProjectPlanner {

    private final AgentFactory agentFactory;

    public TeamLeadProjectPlanner(AgentFactory agentFactory) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
    }

    @Override
    public TaskGraph plan(String projectId, OrchestrationEngine.ProjectRequest request) {
        Agent teamLead = agentFactory.create(AgentRole.TEAM_LEAD);
        Task planningTask = newTask(TaskId.random(), "Plan project", request.featureRequest(), AgentRole.TEAM_LEAD);

        Agent.Response response = teamLead.handle(
                new Agent.Request(planningTask, request.featureRequest(), Map.of(), Map.of()),
                new Agent.Context(projectId, planningTask.id().value(), Map.of()));

        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        List<Map<String, Object>> taskSpecs = extractTasks(response);
        if (taskSpecs.isEmpty()) {
            graph.addTask(newTask(TaskId.random(), "Clarify and plan the request",
                    request.featureRequest(), AgentRole.TEAM_LEAD));
            return graph;
        }

        Map<String, TaskId> logicalToTaskId = new HashMap<>();
        for (Map<String, Object> spec : taskSpecs) {
            TaskId taskId = TaskId.random();
            String logicalId = asString(spec.get("id"), taskId.value());
            logicalToTaskId.put(logicalId, taskId);
            graph.addTask(newTask(taskId,
                    asString(spec.get("title"), "Task"),
                    asString(spec.get("description"), ""),
                    parseRole(spec.get("role"))));
        }
        for (Map<String, Object> spec : taskSpecs) {
            TaskId dependent = logicalToTaskId.get(asString(spec.get("id"), null));
            if (dependent == null) {
                continue;
            }
            for (Object dep : asList(spec.get("dependsOn"))) {
                TaskId prerequisite = logicalToTaskId.get(String.valueOf(dep));
                if (prerequisite != null) {
                    graph.addDependency(dependent, prerequisite);
                }
            }
        }
        return graph;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTasks(Agent.Response response) {
        Object tasks = response.structuredOutput().get("tasks");
        List<Map<String, Object>> result = new ArrayList<>();
        if (tasks instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
        }
        return result;
    }

    private Task newTask(TaskId id, String title, String description, AgentRole role) {
        Instant now = Instant.now();
        return new Task(id, title, description, role, WorkflowState.PENDING,
                List.of(), Map.of(), now, now);
    }

    private AgentRole parseRole(Object value) {
        if (value == null) {
            return AgentRole.BACKEND_DEVELOPER;
        }
        try {
            return AgentRole.valueOf(value.toString().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AgentRole.BACKEND_DEVELOPER;
        }
    }

    private static String asString(Object value, String fallback) {
        return value != null ? value.toString() : fallback;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }
}
