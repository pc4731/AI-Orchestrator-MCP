package com.orchestration.web;

import com.orchestration.audit.AuditEventBroadcaster;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.memory.MemoryStore;
import com.orchestration.task.GraphSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
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

    public ProjectController(OrchestrationEngine engine, MemoryStore memoryStore,
                             AuditEventBroadcaster broadcaster) {
        this.engine = engine;
        this.memoryStore = memoryStore;
        this.broadcaster = broadcaster;
    }

    public record SubmitRequest(String featureRequest) {
    }

    public record SubmitResponse(String projectId, String state) {
    }

    @PostMapping("/projects")
    public SubmitResponse submit(@RequestBody SubmitRequest request) {
        OrchestrationEngine.ProjectHandle handle = engine.submit(new OrchestrationEngine.ProjectRequest(
                request.featureRequest(), Map.of(), Optional.of(2_000_000L)));
        return new SubmitResponse(handle.projectId(), handle.state().name());
    }

    @GetMapping("/projects/{id}")
    public Map<String, Object> status(@PathVariable String id) {
        OrchestrationEngine.ProjectStatus status = engine.status(id);
        return Map.of(
                "projectId", status.projectId(),
                "state", status.state().name(),
                "totalTasks", status.totalTasks(),
                "completedTasks", status.completedTasks(),
                "pendingGates", status.pendingGates().stream()
                        .map(g -> Map.of("gateId", g.gateId(), "type", g.type().name(), "prompt", g.prompt()))
                        .toList());
    }

    @GetMapping("/projects/{id}/graph")
    public Map<String, Object> graph(@PathVariable String id) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        memoryStore.latestCheckpoint(id).ifPresent(checkpoint -> {
            GraphSnapshot snapshot = GraphSnapshot.fromBytes(checkpoint.state());
            for (GraphSnapshot.TaskNode node : snapshot.nodes()) {
                nodes.add(Map.of(
                        "id", node.id(),
                        "title", node.title(),
                        "role", node.assignedRole() == null ? "" : node.assignedRole(),
                        "state", node.state(),
                        "dependsOn", node.dependsOn()));
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
                emitter.send(SseEmitter.event().name("audit").data(Map.of(
                        "projectId", String.valueOf(event.projectId()),
                        "taskId", String.valueOf(event.taskId()),
                        "actor", event.actor(),
                        "type", event.type().name(),
                        "summary", event.summary(),
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
}
