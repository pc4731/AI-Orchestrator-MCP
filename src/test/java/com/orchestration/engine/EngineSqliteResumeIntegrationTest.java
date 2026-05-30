package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentRole;
import com.orchestration.audit.InMemoryAuditLog;
import com.orchestration.memory.SqliteMemoryStore;
import com.orchestration.task.InMemoryTaskGraph;
import com.orchestration.task.Task;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * End-to-end check that the engine's checkpoints really persist through the SQLite-backed
 * {@link SqliteMemoryStore} and that a fresh engine can resume from them without repeating work.
 */
class EngineSqliteResumeIntegrationTest {

    private static Task pending(String id, AgentRole role) {
        return new Task(new TaskId(id), id, "", role, WorkflowState.PENDING,
                List.of(), Map.of(), Instant.now(), Instant.now());
    }

    private static Agent.Response completed() {
        return new Agent.Response(Agent.Outcome.COMPLETED, Map.of(), List.of(),
                Agent.Confidence.HIGH, List.of(), Optional.empty());
    }

    private static ProjectPlanner twoTaskPlanner() {
        return (projectId, request) -> {
            InMemoryTaskGraph graph = new InMemoryTaskGraph();
            graph.addTask(pending("a", AgentRole.BACKEND_ARCHITECT));
            graph.addTask(pending("b", AgentRole.BACKEND_DEVELOPER));
            graph.addDependency(new TaskId("b"), new TaskId("a"));
            return graph;
        };
    }

    @Test
    void checkpointsToSqliteAndResumesWithoutReprocessing() throws Exception {
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            DefaultOrchestrationEngine engine1 = new DefaultOrchestrationEngine(
                    twoTaskPlanner(), (p, t) -> completed(), memory, new InMemoryAuditLog());

            var handle = engine1.submit(new OrchestrationEngine.ProjectRequest("x", Map.of(), Optional.empty()));
            assertEquals(WorkflowState.DONE, engine1.awaitSettled(handle.projectId(), Duration.ofSeconds(5)));
            assertFalse(memory.checkpoints(handle.projectId()).isEmpty());

            AtomicInteger reprocessed = new AtomicInteger();
            DefaultOrchestrationEngine engine2 = new DefaultOrchestrationEngine(
                    twoTaskPlanner(),
                    (p, t) -> {
                        reprocessed.incrementAndGet();
                        return completed();
                    },
                    memory, new InMemoryAuditLog());

            var resumed = engine2.resume(handle.projectId());
            assertEquals(WorkflowState.DONE, resumed.state());
            assertEquals(0, reprocessed.get());
            assertEquals(2, engine2.status(handle.projectId()).completedTasks());
        } finally {
            memory.close();
        }
    }
}
