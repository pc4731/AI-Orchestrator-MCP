package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentRole;
import com.orchestration.audit.AuditLog;
import com.orchestration.memory.MemoryStore;
import com.orchestration.task.InMemoryTaskGraph;
import com.orchestration.task.Task;
import com.orchestration.task.TaskGraph;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultOrchestrationEngineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static Task pendingTask(String id, AgentRole role) {
        return new Task(new TaskId(id), id, "desc", role,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
    }

    private static Agent.Response response(Agent.Outcome outcome) {
        return new Agent.Response(outcome, Map.of(), List.of(), Agent.Confidence.HIGH, List.of(),
                outcome == Agent.Outcome.INSUFFICIENT_INFORMATION
                        ? Optional.of("need more info") : Optional.empty());
    }

    /** Planner that builds a two-task linear graph: b depends on a. */
    private static ProjectPlanner linearGraphPlanner() {
        return (projectId, request) -> {
            InMemoryTaskGraph graph = new InMemoryTaskGraph();
            graph.addTask(pendingTask("a", AgentRole.BACKEND_ARCHITECT));
            graph.addTask(pendingTask("b", AgentRole.BACKEND_DEVELOPER));
            graph.addDependency(new TaskId("b"), new TaskId("a"));
            return graph;
        };
    }

    @Test
    void runsLinearGraphToCompletionAndCheckpoints() throws Exception {
        FakeMemoryStore memory = new FakeMemoryStore();
        AtomicInteger processed = new AtomicInteger();
        TaskProcessor processor = (projectId, task) -> {
            processed.incrementAndGet();
            return response(Agent.Outcome.COMPLETED);
        };
        DefaultOrchestrationEngine engine =
                new DefaultOrchestrationEngine(linearGraphPlanner(), processor, memory, new FakeAuditLog());

        var handle = engine.submit(new OrchestrationEngine.ProjectRequest("build X", Map.of(), Optional.empty()));
        WorkflowState settled = engine.awaitSettled(handle.projectId(), TIMEOUT);

        assertEquals(WorkflowState.DONE, settled);
        assertEquals(2, processed.get());
        OrchestrationEngine.ProjectStatus status = engine.status(handle.projectId());
        assertEquals(2, status.totalTasks());
        assertEquals(2, status.completedTasks());
        assertTrue(memory.latestCheckpoint(handle.projectId()).isPresent());
    }

    @Test
    void resumeOnFreshEngineDoesNotReprocessCompletedWork() throws Exception {
        FakeMemoryStore memory = new FakeMemoryStore();

        DefaultOrchestrationEngine first = new DefaultOrchestrationEngine(
                linearGraphPlanner(), (p, t) -> response(Agent.Outcome.COMPLETED), memory, new FakeAuditLog());
        var handle = first.submit(new OrchestrationEngine.ProjectRequest("build X", Map.of(), Optional.empty()));
        assertEquals(WorkflowState.DONE, first.awaitSettled(handle.projectId(), TIMEOUT));

        // A brand-new engine resuming from the shared checkpoint store must not re-run any task.
        AtomicInteger reprocessed = new AtomicInteger();
        DefaultOrchestrationEngine second = new DefaultOrchestrationEngine(
                linearGraphPlanner(),
                (p, t) -> {
                    reprocessed.incrementAndGet();
                    return response(Agent.Outcome.COMPLETED);
                },
                memory, new FakeAuditLog());

        var resumed = second.resume(handle.projectId());
        assertEquals(WorkflowState.DONE, resumed.state());
        assertEquals(0, reprocessed.get());
        assertEquals(2, second.status(handle.projectId()).completedTasks());
    }

    @Test
    void escalationOpensGateAndApprovalResumesToCompletion() throws Exception {
        FakeMemoryStore memory = new FakeMemoryStore();
        AtomicBoolean escalatedOnce = new AtomicBoolean(false);
        TaskProcessor processor = (projectId, task) -> {
            if (escalatedOnce.compareAndSet(false, true)) {
                return response(Agent.Outcome.INSUFFICIENT_INFORMATION);
            }
            return response(Agent.Outcome.COMPLETED);
        };
        ProjectPlanner singleTask = (projectId, request) -> {
            InMemoryTaskGraph graph = new InMemoryTaskGraph();
            graph.addTask(pendingTask("only", AgentRole.TEAM_LEAD));
            return graph;
        };
        DefaultOrchestrationEngine engine =
                new DefaultOrchestrationEngine(singleTask, processor, memory, new FakeAuditLog());

        var handle = engine.submit(new OrchestrationEngine.ProjectRequest("ambiguous", Map.of(), Optional.empty()));
        assertEquals(WorkflowState.NEEDS_CLARIFICATION, engine.awaitSettled(handle.projectId(), TIMEOUT));

        OrchestrationEngine.ProjectStatus blocked = engine.status(handle.projectId());
        assertEquals(1, blocked.pendingGates().size());
        String gateId = blocked.pendingGates().get(0).gateId();

        engine.decideGate(gateId, new OrchestrationEngine.GateDecision(true, "human", Optional.of("here is info")));
        assertEquals(WorkflowState.DONE, engine.awaitSettled(handle.projectId(), TIMEOUT));
        assertTrue(engine.status(handle.projectId()).pendingGates().isEmpty());
    }

    @Test
    void failedTaskMarksProjectFailedNotDone() throws Exception {
        FakeMemoryStore memory = new FakeMemoryStore();
        TaskProcessor processor = (projectId, task) -> response(Agent.Outcome.FAILED);
        ProjectPlanner singleTask = (projectId, request) -> {
            InMemoryTaskGraph graph = new InMemoryTaskGraph();
            graph.addTask(pendingTask("only", AgentRole.QA_ENGINEER));
            return graph;
        };
        DefaultOrchestrationEngine engine =
                new DefaultOrchestrationEngine(singleTask, processor, memory, new FakeAuditLog());

        var handle = engine.submit(new OrchestrationEngine.ProjectRequest("build X", Map.of(), Optional.empty()));

        // The lone task FAILED — even though every task is now terminal, the project must NOT be DONE.
        assertEquals(WorkflowState.FAILED, engine.awaitSettled(handle.projectId(), TIMEOUT));
    }

    // ------------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------------

    private static final class FakeMemoryStore implements MemoryStore {
        private final Map<String, List<Checkpoint>> checkpoints = new ConcurrentHashMap<>();

        @Override public void put(MemoryEntry entry) { }
        @Override public Optional<MemoryEntry> get(String key) { return Optional.empty(); }
        @Override public List<MemoryEntry> query(Query query) { return List.of(); }
        @Override public void delete(String key) { }

        @Override public void saveCheckpoint(Checkpoint checkpoint) {
            checkpoints.computeIfAbsent(checkpoint.projectId(), k -> new CopyOnWriteArrayList<>()).add(checkpoint);
        }

        @Override public Optional<Checkpoint> latestCheckpoint(String projectId) {
            return checkpoints.getOrDefault(projectId, List.of()).stream()
                    .max(Comparator.comparingLong(Checkpoint::sequence));
        }

        @Override public List<Checkpoint> checkpoints(String projectId) {
            return List.copyOf(checkpoints.getOrDefault(projectId, List.of()));
        }
    }

    private static final class FakeAuditLog implements AuditLog {
        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override public void record(AuditEvent event) { events.add(event); }
        @Override public List<AuditEvent> forProject(String projectId) {
            return events.stream().filter(e -> projectId.equals(e.projectId())).toList();
        }
        @Override public List<AuditEvent> forTask(String taskId) {
            return events.stream().filter(e -> taskId.equals(e.taskId())).toList();
        }
    }
}
