package com.orchestration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentPrompts;
import com.orchestration.agent.AgentRole;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.feedback.FeedbackReporter;
import com.orchestration.knowledge.ProjectKnowledgeStore;
import com.orchestration.memory.MemoryStore;
import com.orchestration.task.GraphSnapshot;
import com.orchestration.web.ActiveProject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ActiveProject activeProject;
    private final ProjectKnowledgeStore knowledgeStore; // optional; used by explain(remember=true)
    private final String workspaceDir;
    private final FeedbackReporter feedbackReporter; // optional; delivers the end-of-run retrospective
    private final McpResponseMapper mapper = new McpResponseMapper();

    private volatile String activeProjectId;
    // taskId -> rememberProject, for one-off "explain a prebuilt project" tasks (not engine tasks).
    private final Map<String, Boolean> explainTasks = new ConcurrentHashMap<>();
    // End-of-run retrospective state for the active project.
    private volatile boolean retrospectiveEnabled;
    private volatile boolean retrospectiveDelivered;
    private volatile String retrospectiveTaskId;

    public OrchestrationMcpService(OrchestrationEngine engine, McpBridge bridge,
                                   MemoryStore memoryStore, ActiveProject activeProject) {
        this(engine, bridge, memoryStore, activeProject, null, ".", null);
    }

    public OrchestrationMcpService(OrchestrationEngine engine, McpBridge bridge,
                                   MemoryStore memoryStore, ActiveProject activeProject,
                                   ProjectKnowledgeStore knowledgeStore, String workspaceDir) {
        this(engine, bridge, memoryStore, activeProject, knowledgeStore, workspaceDir, null);
    }

    public OrchestrationMcpService(OrchestrationEngine engine, McpBridge bridge,
                                   MemoryStore memoryStore, ActiveProject activeProject,
                                   ProjectKnowledgeStore knowledgeStore, String workspaceDir,
                                   FeedbackReporter feedbackReporter) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
        this.activeProject = Objects.requireNonNull(activeProject, "activeProject");
        this.knowledgeStore = knowledgeStore;
        this.workspaceDir = (workspaceDir == null || workspaceDir.isBlank()) ? "." : workspaceDir;
        this.feedbackReporter = feedbackReporter;
    }

    /** Start a project from a feature request; returns once the first agent task is ready. */
    public Map<String, Object> start(String featureRequest) {
        return start(featureRequest, false, false);
    }

    public Map<String, Object> start(String featureRequest, boolean rememberProject) {
        return start(featureRequest, rememberProject, false);
    }

    /**
     * Start a project. {@code rememberProject} opts in to the persistent project brain; {@code
     * retrospective} opts in to the end-of-run analysis of friction with the orchestrator, which is
     * delivered to the maintainer (email, else the backlog file).
     */
    public Map<String, Object> start(String featureRequest, boolean rememberProject,
                                     boolean retrospective) {
        if (featureRequest == null || featureRequest.isBlank()) {
            return Map.of("error", "featureRequest is required");
        }
        bridge.armStart();
        retrospectiveEnabled = retrospective && feedbackReporter != null;
        retrospectiveDelivered = false;
        retrospectiveTaskId = null;
        Map<String, Object> options = Map.of("rememberProject", rememberProject);
        Thread.ofVirtual().name("mcp-project").start(() -> {
            try {
                // tokenBudget empty -> the engine applies the configured ceiling (budgets.yml).
                engine.submit(new OrchestrationEngine.ProjectRequest(featureRequest, options, Optional.empty()));
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
        activeProject.set(projectId); // let the read-only dashboard follow this project
        return Map.of(
                "projectId", projectId,
                "nextAction", "CALL_NEXT",
                "message", "Project started. Run the loop AUTONOMOUSLY: call orchestrate_next, act as "
                        + "the agent it returns, call orchestrate_submit, and repeat until nextAction is "
                        + "STOP. The team may pause to ask the USER clarifying questions or to confirm "
                        + "its understanding. These are answerable in BOTH the web dashboard (by voice "
                        + "or text) AND here: when you are idle, orchestrate_next returns nextAction "
                        + "ASK_USER — relay it to the user and submit ONLY their real answer. Whichever "
                        + "channel answers first wins; if the user used the dashboard, your submit will "
                        + "say it is already completed — just call orchestrate_next again. NEVER answer "
                        + "a user question yourself.");
    }

    /**
     * Explain an existing, prebuilt project the team has no context of. Parks a single
     * PROJECT_EXPLAINER task (no engine, no build): Claude reads the project at {@code path} and
     * produces an explanation. When {@code rememberProject} is true and a knowledge store is wired,
     * the explanation is also saved as the project brief so future sessions start with context.
     */
    public Map<String, Object> explain(String path, String question, boolean rememberProject) {
        String target = (path == null || path.isBlank()) ? workspaceDir : path.trim();
        String taskId = UUID.randomUUID().toString();
        StringBuilder instructions = new StringBuilder(
                "Explore and explain the existing, prebuilt project located at: ").append(target)
                .append("\nRead its real files (entry points, build/config files, source, tests, docs) "
                        + "with your tools, then explain what it does and how it works. Do NOT modify "
                        + "any files — this is an explanation, not a change.");
        if (question != null && !question.isBlank()) {
            instructions.append("\nFocus especially on: ").append(question.trim());
        }
        McpBridge.PendingTask task = new McpBridge.PendingTask(
                taskId, "explain-" + taskId, AgentRole.PROJECT_EXPLAINER.name(),
                "Explain the project", instructions.toString(),
                AgentPrompts.defaultPrompt(AgentRole.PROJECT_EXPLAINER), instructions.toString(),
                "{\"status\":\"COMPLETED\",\"output\":{\"explanation\":\"Markdown explanation\","
                        + "\"summary\":\"one-paragraph TL;DR\"}}",
                Map.of(), McpBridge.Audience.AGENT);

        explainTasks.put(taskId, rememberProject);
        // Park it; the dispatch blocks until Claude submits — discard the result here, submit() owns it.
        Thread.ofVirtual().name("mcp-explain").start(() -> {
            try {
                bridge.dispatch(task);
            } catch (RuntimeException e) {
                System.err.println("[mcp] explain dispatch failed: " + e);
            }
        });
        return Map.of(
                "nextAction", "CALL_NEXT",
                "message", "Call orchestrate_next to get the PROJECT_EXPLAINER task, act as it (read the "
                        + "project at " + target + " and explain it), then call orchestrate_submit with "
                        + "the explanation. This is a one-off read-only task — after it, present the "
                        + "explanation to the user."
                        + (rememberProject ? " It will also be saved as the project brief." : ""));
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
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("task", task);
            response.put("nextAction", "SUBMIT");
            response.put("hint", "Act as this agent now, then immediately call orchestrate_submit "
                    + "with this taskId and your result. Do not ask the user — keep the loop going.");
            return response;
        }
        // Dual-channel human Q&A: with no agent task ready, if the team is waiting on the user,
        // ALSO surface the question here so it can be answered in the CLI chat — the UI shows it in
        // parallel the whole time, and whichever channel answers first wins (the other then finds it
        // already completed). Agent work is polled first above, so this never starves the loop.
        List<McpBridge.PendingTask> openQuestions = bridge.pendingUserQuestions();
        if (!openQuestions.isEmpty()) {
            return userQuery(openQuestions.get(0));
        }
        String state = currentState();
        // End-of-run retrospective: before reporting a terminal state, run one final reflection on
        // friction with the orchestrator (most valuable on FAILED/BLOCKED) and deliver it to you.
        if (retrospectiveEnabled && !retrospectiveDelivered
                && ("DONE".equals(state) || "FAILED".equals(state) || "BLOCKED".equals(state))) {
            return retrospectiveTask(state);
        }
        if ("DONE".equals(state)) {
            return Map.of("status", state, "nextAction", "STOP",
                    "message", "Project DONE. Loop complete — summarize the result for the user. "
                            + "Generated code is committed under data/repo; see RUN.md for how to run it.");
        }
        if ("FAILED".equals(state) || "BLOCKED".equals(state)) {
            // BLOCKED means work is stuck (e.g. a build that couldn't be fixed left a task in review).
            // Never report this as success — tell the user plainly what is unresolved.
            return Map.of("status", state, "nextAction", "STOP",
                    "message", "Project " + state + " — it is NOT successfully done. Likely a build/test "
                            + "failure that could not be fixed, or a blocked task. Inspect data/repo and "
                            + "the failing tests, report the blocker to the user, and do not present this "
                            + "as a finished product.");
        }
        if ("NEEDS_CLARIFICATION".equals(state)) {
            // A worker is blocked for missing information that wasn't resolved (no answer from the
            // user). Stop rather than polling forever — surface the open question to the user.
            return Map.of("status", state, "nextAction", "STOP",
                    "message", "Project is blocked awaiting clarification that wasn't resolved. Get the "
                            + "missing information from the user, then start a fresh run with it. Do NOT "
                            + "keep calling orchestrate_next — nothing will become ready until it's answered.");
        }
        // Not finished but nothing ready this instant (a task is running): tell the client to retry.
        return Map.of("status", state, "nextAction", "CALL_NEXT",
                "message", "No task ready this moment; call orchestrate_next again to continue the loop.");
    }

    /**
     * Surface a pending USER question to the CLI as an ASK_USER prompt. Non-destructive: the same
     * question stays available in the UI ({@code /api/questions}); whichever channel answers first
     * completes it and the other sees it gone.
     */
    private Map<String, Object> userQuery(McpBridge.PendingTask t) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("taskId", t.taskId());
        query.put("forUser", true);
        query.put("title", t.title());
        query.put("questionsForUser", t.description());
        query.put("responseSchema", t.responseSchema());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userQuery", query);
        response.put("nextAction", "ASK_USER");
        response.put("message", "The team is waiting on the USER. This is also open in the web "
                + "dashboard (answerable there by voice or text). If the user replies to you here, "
                + "relay questionsForUser verbatim and submit ONLY their real answer via "
                + "orchestrate_submit with this taskId, output matching responseSchema. If they answer "
                + "in the dashboard instead, your submit will report it already completed — just call "
                + "orchestrate_next again. Do NOT answer on the user's behalf.");
        return response;
    }

    /** Deliver an agent's result for a task and advance the workflow. */
    public Map<String, Object> submit(String taskId, JsonNode result) {
        if (taskId == null || taskId.isBlank()) {
            return Map.of("accepted", false, "error", "taskId is required");
        }
        Agent.Response response = mapper.parse(result);
        if (explainTasks.containsKey(taskId)) {
            return submitExplanation(taskId, response);
        }
        if (taskId.equals(retrospectiveTaskId)) {
            return submitRetrospective(response);
        }
        boolean accepted = bridge.complete(taskId, response);
        if (!accepted) {
            // Most often a benign race: a USER question was already answered in the dashboard. Return
            // a structured signal (not an error) so the loop just continues instead of assuming failure.
            return Map.of("accepted", false, "outcome", "ALREADY_COMPLETED", "nextAction", "CALL_NEXT",
                    "message", "That task was already completed (e.g. the user answered in the "
                            + "dashboard). Nothing to do — call orchestrate_next to continue.");
        }
        String state = currentState();
        boolean finished = "DONE".equals(state) || "FAILED".equals(state);
        return Map.of(
                "accepted", true,
                "outcome", response.outcome().name(),
                "projectState", state,
                "nextAction", finished ? "STOP" : "CALL_NEXT",
                "message", finished
                        ? "Project " + state + ". Loop complete — summarize for the user."
                        : "Recorded. Immediately call orchestrate_next for the next task — keep looping "
                                + "autonomously until nextAction is STOP.");
    }

    /** Deliver a PROJECT_EXPLAINER result: a one-off explanation, optionally saved as the brief. */
    private Map<String, Object> submitExplanation(String taskId, Agent.Response response) {
        boolean accepted = bridge.complete(taskId, response);
        if (!accepted) {
            return Map.of("accepted", false, "error", "unknown or already-completed taskId: " + taskId);
        }
        boolean remember = Boolean.TRUE.equals(explainTasks.remove(taskId));
        Object explanation = response.structuredOutput().getOrDefault("explanation",
                response.structuredOutput().get("summary"));
        boolean saved = false;
        if (remember && knowledgeStore != null && explanation != null
                && !explanation.toString().isBlank()) {
            knowledgeStore.save(explanation.toString(), taskId, AgentRole.PROJECT_EXPLAINER.name());
            saved = true;
        }
        return Map.of(
                "accepted", true,
                "nextAction", "STOP",
                "savedAsProjectBrief", saved,
                "message", "Explanation complete — present output.explanation to the user."
                        + (saved ? " It was also saved as the project brief for future sessions." : ""));
    }

    /** Synthesize the final RETROSPECTIVE_ANALYST task (not an engine/bridge task). */
    private Map<String, Object> retrospectiveTask(String state) {
        if (retrospectiveTaskId == null) {
            retrospectiveTaskId = "retro-" + UUID.randomUUID();
        }
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", retrospectiveTaskId);
        task.put("role", AgentRole.RETROSPECTIVE_ANALYST.name());
        task.put("title", "Retrospective: how to improve the orchestrator");
        task.put("persona", AgentPrompts.defaultPrompt(AgentRole.RETROSPECTIVE_ANALYST));
        task.put("instructions", "The project just finished with outcome: " + state + ". Run a "
                + "retrospective on THIS run, focused ONLY on friction with the orchestration SYSTEM "
                + "itself (missing roles/capabilities, rigid schemas, weak hand-offs/context, the "
                + "budget, no way to ask the user or run a command, repeated rework/blocked tasks) — "
                + "not bugs in the built project. List concrete improvements to the orchestrator.");
        task.put("responseSchema", "{\"status\":\"COMPLETED\",\"output\":{\"improvements\":"
                + "[{\"problem\":\"...\",\"impact\":\"...\",\"suggestion\":\"...\","
                + "\"severity\":\"HIGH|MEDIUM|LOW\"}],\"summary\":\"...\"}}");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("task", task);
        response.put("nextAction", "SUBMIT");
        response.put("hint", "Final step: reflect on the run you just orchestrated and submit your "
                + "improvement notes for the orchestrator. After submitting, the project is complete.");
        return response;
    }

    /** Deliver the retrospective (email, else backlog file) and end the run. */
    private Map<String, Object> submitRetrospective(Agent.Response response) {
        retrospectiveDelivered = true;
        String report = formatImprovements(response);
        String delivery = feedbackReporter == null || report.isBlank()
                ? "no feedback to send"
                : feedbackReporter.deliver(activeProjectId, currentState(), report);
        return Map.of(
                "accepted", true,
                "nextAction", "STOP",
                "feedbackDelivery", delivery,
                "message", "Retrospective delivered (" + delivery + "). Project complete — summarize "
                        + "the result for the user.");
    }

    /** Render the analyst's improvements into a readable Markdown report for delivery. */
    private static String formatImprovements(Agent.Response r) {
        StringBuilder sb = new StringBuilder();
        Object summary = r.structuredOutput().get("summary");
        if (summary != null && !summary.toString().isBlank()) {
            sb.append(summary).append("\n\n");
        }
        Object improvements = r.structuredOutput().get("improvements");
        if (improvements instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    sb.append("- [").append(field(m, "severity")).append("] ")
                            .append(field(m, "problem")).append('\n');
                    String impact = field(m, "impact");
                    String suggestion = field(m, "suggestion");
                    if (!impact.isBlank()) {
                        sb.append("    impact: ").append(impact).append('\n');
                    }
                    if (!suggestion.isBlank()) {
                        sb.append("    suggestion: ").append(suggestion).append('\n');
                    }
                } else if (item != null && !item.toString().isBlank()) {
                    sb.append("- ").append(item).append('\n');
                }
            }
        }
        return sb.toString().strip();
    }

    private static String field(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString();
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
