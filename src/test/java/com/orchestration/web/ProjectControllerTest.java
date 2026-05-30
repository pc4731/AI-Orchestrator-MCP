package com.orchestration.web;

import com.orchestration.audit.AuditEventBroadcaster;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.memory.SqliteMemoryStore;
import com.orchestration.task.WorkflowState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectControllerTest {

    /** Minimal engine stub for exercising the controller's mapping. */
    private static OrchestrationEngine engineStub() {
        return new OrchestrationEngine() {
            @Override public ProjectHandle submit(ProjectRequest request) {
                return new ProjectHandle("p1", WorkflowState.PENDING);
            }
            @Override public ProjectHandle resume(String projectId) {
                return new ProjectHandle(projectId, WorkflowState.DONE);
            }
            @Override public ProjectStatus status(String projectId) {
                return new ProjectStatus(projectId, WorkflowState.IN_PROGRESS, 2, 1, 0, List.of());
            }
            @Override public void decideGate(String gateId, GateDecision decision) { }
            @Override public void shutdown() { }
        };
    }

    @Test
    void submitReturnsProjectId() {
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            ProjectController controller = new ProjectController(engineStub(), memory, new AuditEventBroadcaster());
            ProjectController.SubmitResponse response =
                    controller.submit(new ProjectController.SubmitRequest("build a thing"));
            assertEquals("p1", response.projectId());
        } finally {
            memory.close();
        }
    }

    @Test
    void statusIsMappedToJsonShape() {
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            ProjectController controller = new ProjectController(engineStub(), memory, new AuditEventBroadcaster());
            Map<String, Object> status = controller.status("p1");
            assertEquals("IN_PROGRESS", status.get("state"));
            assertEquals(2, status.get("totalTasks"));
            assertEquals(1, status.get("completedTasks"));
        } finally {
            memory.close();
        }
    }

    @Test
    void graphIsEmptyWhenNoCheckpointExists() {
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            ProjectController controller = new ProjectController(engineStub(), memory, new AuditEventBroadcaster());
            Map<String, Object> graph = controller.graph("unknown");
            assertTrue(((List<?>) graph.get("nodes")).isEmpty());
        } finally {
            memory.close();
        }
    }
}
