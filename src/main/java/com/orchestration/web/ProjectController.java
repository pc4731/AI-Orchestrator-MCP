package com.orchestration.web;

import com.orchestration.audit.AuditEventBroadcaster;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.memory.MemoryStore;
import com.orchestration.task.GraphSnapshot;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST + SSE API backing the live agent-interaction UI.
 *
 * <ul>
 *   <li>{@code POST /api/projects} — submit a feature request, returns the project id.</li>
 *   <li>{@code GET  /api/projects/{id}} — current status (state, task counts, gates).</li>
 *   <li>{@code GET  /api/projects/{id}/graph} — the task DAG (nodes + states + edges), read from
 *       the latest checkpoint so the browser can draw it.</li>
 *   <li>{@code GET  /api/stream} — Server-Sent Events of every audit event, so the UI shows agents
 *       being dispatched and responding in real time.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ProjectController {

    private final OrchestrationEngine engine;
    private final MemoryStore memoryStore;
    private final AuditEventBroadcaster broadcaster;
    private final ActiveProject activeProject;
    private final boolean observerMode;

    public ProjectController(OrchestrationEngine engine, MemoryStore memoryStore,
                             AuditEventBroadcaster broadcaster, ActiveProject activeProject,
                             Environment environment) {
        this.engine = engine;
        this.memoryStore = memoryStore;
        this.broadcaster = broadcaster;
        this.activeProject = activeProject;
        // Under the mcp profile the browser only observes; Claude Code drives projects.
        this.observerMode = environment.acceptsProfiles(Profiles.of("mcp"));
    }

    /** UI bootstrap: tells the page whether it can submit ("interactive") or only watch ("observer"). */
    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of("mode", observerMode ? "observer" : "interactive");
    }

    /** The project the dashboard should follow (set by the MCP driver). Empty until one starts. */
    @GetMapping("/active")
    public Map<String, Object> active() {
        return Map.of("projectId", activeProject.get().orElse(""));
    }

    public record SubmitRequest(String featureRequest) {
    }

    public record SubmitResponse(String projectId, String state) {
    }

    @PostMapping("/projects")
    public SubmitResponse submit(@RequestBody SubmitRequest request) {
        OrchestrationEngine.ProjectHandle handle = engine.submit(new OrchestrationEngine.ProjectRequest(
                request.featureRequest(), Map.of(), Optional.of(2_000_000L)));
        // Record it as the active project so a browser refresh (or a second tab) can re-discover the
        // running project via /api/active instead of losing the live view to client-side state reset.
        activeProject.set(handle.projectId());
        return new SubmitResponse(handle.projectId(), handle.state().name());
    }

    @GetMapping("/projects/{id}")
    public Map<String, Object> status(@PathVariable String id) {
        OrchestrationEngine.ProjectStatus status = engine.status(id);
        return Map.of(
                "projectId", safe(status.projectId()),
                "state", status.state().name(),
                "totalTasks", status.totalTasks(),
                "completedTasks", status.completedTasks(),
                // A gate's prompt can be null while it is still being populated; Map.of rejects nulls
                // (→ NPE → 500), so build a null-tolerant map and default the prompt.
                "pendingGates", status.pendingGates().stream()
                        .map(g -> {
                            Map<String, Object> gate = new LinkedHashMap<>();
                            gate.put("gateId", safe(g.gateId()));
                            gate.put("type", g.type() == null ? "" : g.type().name());
                            gate.put("prompt", safe(g.prompt()));
                            return gate;
                        })
                        .toList());
    }

    @GetMapping("/projects/{id}/graph")
    public Map<String, Object> graph(@PathVariable String id) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        memoryStore.latestCheckpoint(id).ifPresent(checkpoint -> {
            GraphSnapshot snapshot = GraphSnapshot.fromBytes(checkpoint.state());
            for (GraphSnapshot.TaskNode node : snapshot.nodes()) {
                // Only dependsOn is guaranteed non-null by TaskNode; id/title/state can be null on a
                // half-populated node mid-run. Map.of rejects nulls (→ NPE → 500), so default them all.
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", safe(node.id()));
                n.put("title", safe(node.title()));
                n.put("role", safe(node.assignedRole()));
                n.put("state", safe(node.state()));
                n.put("dependsOn", node.dependsOn());
                nodes.add(n);
            }
        });
        return Map.of("nodes", nodes);
    }

    @GetMapping("/stream")
    public SseEmitter stream(@RequestParam(required = false) String projectId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        Runnable unsubscribe = broadcaster.subscribe(event -> {
            if (projectId != null && !projectId.equals(event.projectId())) {
                return;
            }
            try {
                Map<String, Object> d = event.details();
                emitter.send(SseEmitter.event().name("audit").data(Map.of(
                        "projectId", String.valueOf(event.projectId()),
                        "taskId", String.valueOf(event.taskId()),
                        "actor", event.actor(),
                        "type", event.type().name(),
                        "summary", event.summary(),
                        // Enriched fields for the live view (empty string when absent).
                        "role", String.valueOf(d.getOrDefault("role", "")),
                        "collaborator", String.valueOf(d.getOrDefault("collaborator", "")),
                        "prompt", String.valueOf(d.getOrDefault("prompt", "")),
                        "detail", String.valueOf(d.getOrDefault("detail", "")),
                        "at", event.at().toString())));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        });
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(() -> {
            unsubscribe.run();
            emitter.complete();
        });
        emitter.onError(e -> unsubscribe.run());
        return emitter;
    }

    /** An unknown project/gate id is a "not found", not a server fault: the engine signals it with
     *  IllegalArgumentException, which would otherwise surface as a confusing HTTP 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleUnknown(IllegalArgumentException e) {
        return Map.of("error", e.getMessage() == null ? "Not found" : e.getMessage());
    }

    /** Null-safe string for response maps — {@link Map#of} rejects null values with an NPE. */
    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
