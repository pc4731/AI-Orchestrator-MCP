package com.orchestration.web;

import com.orchestration.audit.AuditEventBroadcaster;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.memory.SqliteMemoryStore;
import com.orchestration.task.WorkflowState;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectControllerTest {

    private static ProjectController controller(OrchestrationEngine engine, SqliteMemoryStore memory) {
        return new ProjectController(engine, memory, new AuditEventBroadcaster(),
                new ActiveProject(), new StandardEnvironment());
    }

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
            ProjectController.SubmitResponse response =
                    controller(engineStub(), memory).submit(new ProjectController.SubmitRequest("build a thing"));
            assertEquals("p1", response.projectId());
        } finally {
            memory.close();
        }
    }

    @Test
    void statusIsMappedToJsonShape() {
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            Map<String, Object> status = controller(engineStub(), memory).status("p1");
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
            Map<String, Object> graph = controller(engineStub(), memory).graph("unknown");
            assertTrue(((List<?>) graph.get("nodes")).isEmpty());
        } finally {
            memory.close();
        }
    }

    @Test
    void infoReportsObserverModeUnderMcpProfile() {
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            MockEnvironment mcp = new MockEnvironment();
            mcp.setActiveProfiles("mcp");
            ProjectController controller = new ProjectController(engineStub(), memory,
                    new AuditEventBroadcaster(), new ActiveProject(), mcp);
            assertEquals("observer", controller.info().get("mode"));
        } finally {
            memory.close();
        }
    }

    @Test
    void activeReflectsTheActiveProjectHolder() {
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            ActiveProject active = new ActiveProject();
            active.set("proj-9");
            ProjectController controller = new ProjectController(engineStub(), memory,
                    new AuditEventBroadcaster(), active, new StandardEnvironment());
            assertEquals("proj-9", controller.active().get("projectId"));
        } finally {
            memory.close();
        }
    }
}
