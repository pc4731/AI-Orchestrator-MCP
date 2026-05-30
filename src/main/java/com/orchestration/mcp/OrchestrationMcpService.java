package com.orchestration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestration.agent.Agent;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.memory.MemoryStore;
import com.orchestration.task.GraphSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Implements the four MCP tools that let Claude Code drive the agent team. Bridges the JSON-RPC
 * layer to the orchestration engine and the {@link McpBridge}; holds no transport concerns so it is
 * directly unit-testable (a test can play the role of Claude Code by calling these methods).
 */
public class OrchestrationMcpService {

    private static final long POLL_MILLIS = 4000;
    private static final long START_TIMEOUT_MILLIS = 15000;

    private final OrchestrationEngine engine;
    private final McpBridge bridge;
    private final MemoryStore memoryStore;
    private final McpResponseMapper mapper = new McpResponseMapper();

    private volatile String activeProjectId;

    public OrchestrationMcpService(OrchestrationEngine engine, McpBridge bridge, MemoryStore memoryStore) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
    }

    /** Start a project from a feature request; returns once the first agent task is ready. */
    public Map<String, Object> start(String featureRequest) {
        if (featureRequest == null || featureRequest.isBlank()) {
            return Map.of("error", "featureRequest is required");
        }
        bridge.armStart();
        Thread.ofVirtual().name("mcp-project").start(() -> {
            try {
                engine.submit(new OrchestrationEngine.ProjectRequest(featureRequest, Map.of(), Optional.of(2_000_000L)));
            } catch (RuntimeException e) {
                System.err.println("[mcp] project submit failed: " + e);
            }
        });
        String projectId;
        try {
            projectId = bridge.awaitProjectId(START_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("error", "interrupted while starting project");
        }
        if (projectId == null) {
            return Map.of("error", "timed out starting project");
        }
        activeProjectId = projectId;
        return Map.of(
                "projectId", projectId,
                "message", "Project started. Call orchestrate_next to receive the first agent task.");
    }

    /** Return the next agent task to fulfil, or the project status if none is pending. */
    public Map<String, Object> next() {
        Optional<McpBridge.PendingTask> pending = bridge.poll(POLL_MILLIS);
        if (pending.isPresent()) {
            McpBridge.PendingTask t = pending.get();
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("taskId", t.taskId());
            task.put("role", t.role());
            task.put("title", t.title());
            task.put("description", t.description());
            task.put("persona", t.systemPrompt());
            task.put("instructions", t.instructions());
            task.put("grounding", t.grounding());
            task.put("responseSchema", t.responseSchema());
            task.put("hint", "Produce this agent's output, then call orchestrate_submit with taskId and result.");
            return Map.of("task", task);
        }
        String state = currentState();
        if ("DONE".equals(state) || "FAILED".equals(state)) {
            return Map.of("status", state, "message", "Project finished.");
        }
        return Map.of("status", state, "message", "No task ready yet; call orchestrate_next again.");
    }

    /** Deliver an agent's result for a task and advance the workflow. */
    public Map<String, Object> submit(String taskId, JsonNode result) {
        if (taskId == null || taskId.isBlank()) {
            return Map.of("accepted", false, "error", "taskId is required");
        }
        Agent.Response response = mapper.parse(result);
        boolean accepted = bridge.complete(taskId, response);
        if (!accepted) {
            return Map.of("accepted", false, "error", "unknown or already-completed taskId: " + taskId);
        }
        return Map.of(
                "accepted", true,
                "outcome", response.outcome().name(),
                "projectState", currentState(),
                "message", "Recorded. Call orchestrate_next for the next task.");
    }

    /** Current project status plus the task graph (states + dependencies). */
    public Map<String, Object> status() {
        if (activeProjectId == null) {
            return Map.of("status", "NO_PROJECT", "message", "Call orchestrate_start first.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", activeProjectId);
        result.put("state", currentState());
        try {
            OrchestrationEngine.ProjectStatus s = engine.status(activeProjectId);
            result.put("totalTasks", s.totalTasks());
            result.put("completedTasks", s.completedTasks());
        } catch (RuntimeException e) {
            result.put("totalTasks", 0);
            result.put("completedTasks", 0);
        }
        result.put("graph", graphNodes());
        return result;
    }

    private List<Map<String, Object>> graphNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (activeProjectId == null) {
            return nodes;
        }
        memoryStore.latestCheckpoint(activeProjectId).ifPresent(checkpoint -> {
            GraphSnapshot snapshot = GraphSnapshot.fromBytes(checkpoint.state());
            for (GraphSnapshot.TaskNode node : snapshot.nodes()) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", node.id());
                n.put("title", node.title());
                n.put("role", node.assignedRole() == null ? "" : node.assignedRole());
                n.put("state", node.state());
                n.put("dependsOn", node.dependsOn());
                nodes.add(n);
            }
        });
        return nodes;
    }

    private String currentState() {
        if (activeProjectId == null) {
            return "NO_PROJECT";
        }
        try {
            return engine.status(activeProjectId).state().name();
        } catch (RuntimeException e) {
            return "PLANNING"; // engine has not registered the project until decomposition completes
        }
    }

    String activeProjectId() {
        return activeProjectId;
    }
}
