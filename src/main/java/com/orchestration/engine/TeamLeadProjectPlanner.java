package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentRole;
import com.orchestration.audit.AuditLog;
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
import java.util.UUID;

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
    private final AuditLog auditLog; // optional; when present, planning steps stream to the UI

    public TeamLeadProjectPlanner(AgentFactory agentFactory) {
        this(agentFactory, null);
    }

    public TeamLeadProjectPlanner(AgentFactory agentFactory, AuditLog auditLog) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.auditLog = auditLog;
    }

    private void planAudit(String projectId, String actor, AuditLog.EventType type,
                           String summary, Map<String, Object> details) {
        if (auditLog == null) {
            return;
        }
        auditLog.record(new AuditLog.AuditEvent(UUID.randomUUID().toString(), projectId, null,
                actor, type, summary, details, Instant.now()));
    }

    @Override
    public TaskGraph plan(String projectId, OrchestrationEngine.ProjectRequest request) {
        // 1) Business Analyst elicits/clarifies requirements into a specification.
        String specification = elicitSpecification(projectId, request);

        // 2) Team Lead decomposes the (clarified) request + spec into a role-assigned task graph.
        Agent teamLead = agentFactory.create(AgentRole.TEAM_LEAD);
        Task planningTask = newTask(TaskId.random(), "Plan project", request.featureRequest(), AgentRole.TEAM_LEAD);
        Map<String, String> grounding = specification.isBlank()
                ? Map.of() : Map.of("specification", specification);

        planAudit(projectId, AgentRole.TEAM_LEAD.name(), AuditLog.EventType.PROMPT,
                "TEAM_LEAD decomposing the request into tasks",
                Map.of("role", "TEAM_LEAD", "collaborator", "BUSINESS_ANALYST",
                        "prompt", request.featureRequest()));

        Agent.Response response = teamLead.handle(
                new Agent.Request(planningTask, request.featureRequest(), grounding, Map.of()),
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
            // A task may carry a testCommand override (e.g. the Team Lead specifies [npm, test]
            // for a QA task on a Node project); it flows into task metadata.
            Map<String, Object> metadata = new HashMap<>();
            List<?> testCommand = asList(spec.get("testCommand"));
            if (!testCommand.isEmpty()) {
                metadata.put("testCommand", testCommand.stream().map(String::valueOf).toList());
            }
            graph.addTask(newTask(taskId,
                    asString(spec.get("title"), "Task"),
                    asString(spec.get("description"), ""),
                    parseRole(spec.get("role")),
                    metadata));
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

    /**
     * Run the Business Analyst to turn the raw request into a specification. Best-effort: if BA isn't
     * configured or returns nothing usable, planning proceeds on the raw request.
     */
    private String elicitSpecification(String projectId, OrchestrationEngine.ProjectRequest request) {
        if (!agentFactory.supports(AgentRole.BUSINESS_ANALYST)) {
            return "";
        }
        try {
            Agent ba = agentFactory.create(AgentRole.BUSINESS_ANALYST);
            Task baTask = newTask(TaskId.random(), "Clarify requirements",
                    request.featureRequest(), AgentRole.BUSINESS_ANALYST);
            planAudit(projectId, AgentRole.BUSINESS_ANALYST.name(), AuditLog.EventType.PROMPT,
                    "BUSINESS_ANALYST eliciting requirements",
                    Map.of("role", "BUSINESS_ANALYST", "collaborator", "user",
                            "prompt", request.featureRequest()));
            Agent.Response r = ba.handle(
                    new Agent.Request(baTask, request.featureRequest(), Map.of(), Map.of()),
                    new Agent.Context(projectId, baTask.id().value(), Map.of()));
            Object spec = r.structuredOutput().get("specification");
            String specText = spec != null ? spec.toString() : "";
            planAudit(projectId, AgentRole.BUSINESS_ANALYST.name(), AuditLog.EventType.RESPONSE,
                    "BUSINESS_ANALYST produced a specification",
                    Map.of("role", "BUSINESS_ANALYST", "detail",
                            specText.isBlank() ? "(needs clarification)" : trim(specText)));
            return specText;
        } catch (RuntimeException e) {
            return ""; // never let requirements-gathering crash planning
        }
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
        return newTask(id, title, description, role, Map.of());
    }

    private Task newTask(TaskId id, String title, String description, AgentRole role,
                         Map<String, Object> metadata) {
        Instant now = Instant.now();
        return new Task(id, title, description, role, WorkflowState.PENDING,
                List.of(), metadata, now, now);
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

    private static String trim(String text) {
        String t = text.strip();
        return t.length() <= 280 ? t : t.substring(0, 280) + "…";
    }
}
