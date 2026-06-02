package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.audit.AuditLog;
import com.orchestration.budget.DefaultTokenBudgetManager;
import com.orchestration.budget.TokenBudgetManager;
import com.orchestration.memory.MemoryStore;
import com.orchestration.task.GraphSnapshot;
import com.orchestration.task.InMemoryTaskGraph;
import com.orchestration.task.Task;
import com.orchestration.task.TaskGraph;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default {@link OrchestrationEngine} — the central coordinator.
 *
 * <p><b>Scope of this step (engine core):</b> the project lifecycle, the virtual-thread dispatch
 * loop that runs ready tasks (parallel where the graph allows), task state handling driven by each
 * agent's {@link Agent.Outcome}, human-in-the-loop gates on escalation, and a checkpoint written
 * after every task so a run can resume exactly where it stopped. Agent routing and graph planning
 * are injected via the {@link ProjectPlanner} and {@link TaskProcessor} seams; their real
 * (agent/LLM-backed) implementations land in later steps.
 *
 * <p>This is a plain object (no Spring wiring yet) so it can be unit-tested directly with fakes.
 */
public class DefaultOrchestrationEngine implements OrchestrationEngine {

    private final ProjectPlanner planner;
    private final TaskProcessor processor;
    private final MemoryStore memoryStore;
    private final AuditLog auditLog;
    private final TokenBudgetManager budget;
    private final long defaultProjectBudget;
    private final ExecutorService executor;

    private final Map<String, ProjectExecution> projects = new ConcurrentHashMap<>();

    public DefaultOrchestrationEngine(ProjectPlanner planner,
                                      TaskProcessor processor,
                                      MemoryStore memoryStore,
                                      AuditLog auditLog) {
        this(planner, processor, memoryStore, auditLog,
                new DefaultTokenBudgetManager(), Long.MAX_VALUE);
    }

    public DefaultOrchestrationEngine(ProjectPlanner planner,
                                      TaskProcessor processor,
                                      MemoryStore memoryStore,
                                      AuditLog auditLog,
                                      TokenBudgetManager budget,
                                      long defaultProjectBudget) {
        this(planner, processor, memoryStore, auditLog, budget, defaultProjectBudget,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Visible for tests that want to supply a deterministic executor (unbounded budget). */
    DefaultOrchestrationEngine(ProjectPlanner planner,
                               TaskProcessor processor,
                               MemoryStore memoryStore,
                               AuditLog auditLog,
                               ExecutorService executor) {
        this(planner, processor, memoryStore, auditLog,
                new DefaultTokenBudgetManager(), Long.MAX_VALUE, executor);
    }

    DefaultOrchestrationEngine(ProjectPlanner planner,
                               TaskProcessor processor,
                               MemoryStore memoryStore,
                               AuditLog auditLog,
                               TokenBudgetManager budget,
                               long defaultProjectBudget,
                               ExecutorService executor) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.defaultProjectBudget = defaultProjectBudget;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    // ------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------

    @Override
    public ProjectHandle submit(ProjectRequest request) {
        Objects.requireNonNull(request, "request");
        String projectId = UUID.randomUUID().toString();
        // Register the cost ceiling before any agent runs, so planning usage counts toward it too.
        budget.registerProjectBudget(projectId, request.tokenBudget().orElse(defaultProjectBudget));
        TaskGraph graph = planner.plan(projectId, request);
        ProjectExecution exec = new ProjectExecution(projectId, graph);
        projects.put(projectId, exec);
        audit(projectId, null, AuditLog.EventType.STATE_CHANGE, "Project submitted");
        exec.state = WorkflowState.IN_PROGRESS;
        startDriver(exec);
        return new ProjectHandle(projectId, exec.state);
    }

    @Override
    public ProjectHandle resume(String projectId) {
        ProjectExecution existing = projects.get(projectId);
        if (existing != null && existing.state.isTerminal()) {
            return new ProjectHandle(projectId, existing.state);
        }
        MemoryStore.Checkpoint checkpoint = memoryStore.latestCheckpoint(projectId)
                .orElseThrow(() -> new IllegalStateException("No checkpoint for project: " + projectId));
        InMemoryTaskGraph graph = InMemoryTaskGraph.fromSnapshot(GraphSnapshot.fromBytes(checkpoint.state()));
        ProjectExecution exec = new ProjectExecution(projectId, graph);
        exec.checkpointSeq.set(checkpoint.sequence());
        WorkflowState restored = WorkflowState.valueOf(
                (String) checkpoint.metadata().getOrDefault("projectState", WorkflowState.IN_PROGRESS.name()));
        projects.put(projectId, exec);
        audit(projectId, null, AuditLog.EventType.STATE_CHANGE,
                "Project resumed from checkpoint seq=" + checkpoint.sequence());

        if (restored.isTerminal()) {
            // Nothing left to do; completed tasks are never reprocessed.
            exec.state = restored;
            return new ProjectHandle(projectId, exec.state);
        }
        exec.state = WorkflowState.IN_PROGRESS;
        startDriver(exec);
        return new ProjectHandle(projectId, exec.state);
    }

    @Override
    public ProjectStatus status(String projectId) {
        ProjectExecution exec = require(projectId);
        List<Task> tasks = new ArrayList<>(exec.graph.tasks());
        int done = (int) tasks.stream().filter(t -> t.state() == WorkflowState.DONE).count();
        return new ProjectStatus(projectId, exec.state, tasks.size(), done,
                budget.usedForProject(projectId), List.copyOf(exec.pendingGates.values()));
    }

    @Override
    public void decideGate(String gateId, GateDecision decision) {
        Objects.requireNonNull(decision, "decision");
        for (ProjectExecution exec : projects.values()) {
            PendingGate gate = exec.pendingGates.remove(gateId);
            if (gate == null) {
                continue;
            }
            audit(exec.projectId, null, AuditLog.EventType.GATE,
                    "Gate " + gateId + " decided approved=" + decision.approved());
            if (decision.approved()) {
                reopenClarifiedTasks(exec);
                if (exec.pendingGates.isEmpty()) {
                    exec.paused = false;
                    exec.state = WorkflowState.IN_PROGRESS;
                    startDriver(exec);
                }
            } else {
                finish(exec, WorkflowState.FAILED);
            }
            return;
        }
        throw new IllegalArgumentException("Unknown gate: " + gateId);
    }

    @Override
    public void shutdown() {
        projects.values().forEach(this::checkpoint);
        executor.shutdown();
    }

    // ------------------------------------------------------------------------
    // Dispatch loop (runs on a virtual thread per project)
    // ------------------------------------------------------------------------

    private void startDriver(ProjectExecution exec) {
        exec.driver = executor.submit(() -> driveLoop(exec));
    }

    private void driveLoop(ProjectExecution exec) {
        try {
            while (!exec.paused) {
                // Cost circuit breaker, checked BETWEEN task batches — never mid-task — so a task's
                // internal rework / build-fix loops always run to completion and output quality is
                // never degraded. Only the start of the NEXT batch is gated.
                if (budget.remainingForProject(exec.projectId) <= 0) {
                    audit(exec.projectId, null, AuditLog.EventType.STATE_CHANGE,
                            "Token budget exhausted (used " + budget.usedForProject(exec.projectId)
                                    + "); halting before starting more work");
                    finish(exec, WorkflowState.BLOCKED);
                    return;
                }
                Set<Task> ready = exec.graph.readyTasks();
                if (ready.isEmpty()) {
                    if (exec.graph.isComplete()) {
                        // isComplete() is true when every task is terminal — but FAILED is terminal
                        // too. A project is only DONE when no task failed; otherwise it FAILED, so a
                        // broken build can never be reported as a successful project.
                        boolean anyFailed = exec.graph.tasks().stream()
                                .anyMatch(t -> t.state() == WorkflowState.FAILED);
                        finish(exec, anyFailed ? WorkflowState.FAILED : WorkflowState.DONE);
                    } else if (exec.hasPendingGates()) {
                        exec.state = WorkflowState.NEEDS_CLARIFICATION;
                    } else {
                        // Nothing runnable and not complete: tasks are stuck (e.g. awaiting review).
                        exec.state = WorkflowState.BLOCKED;
                    }
                    return;
                }
                // Mark ready tasks IN_PROGRESS then run them in parallel on virtual threads.
                List<Future<?>> batch = new ArrayList<>(ready.size());
                for (Task task : ready) {
                    exec.graph.updateState(task.id(), WorkflowState.IN_PROGRESS);
                    batch.add(executor.submit(() -> processOne(exec, task)));
                }
                awaitAll(batch);
            }
        } catch (RuntimeException e) {
            audit(exec.projectId, null, AuditLog.EventType.ERROR, "Drive loop failed: " + e);
            finish(exec, WorkflowState.FAILED);
        }
    }

    private void processOne(ProjectExecution exec, Task task) {
        try {
            Agent.Response response = processor.process(exec.projectId, task);
            audit(exec.projectId, task.id().value(), AuditLog.EventType.RESPONSE,
                    "Task processed: outcome=" + response.outcome()
                            + ", confidence=" + response.confidence());
            WorkflowState next = switch (response.outcome()) {
                case COMPLETED -> WorkflowState.DONE;
                case NEEDS_REVIEW -> WorkflowState.IN_REVIEW;
                case ESCALATE, INSUFFICIENT_INFORMATION -> {
                    openGate(exec, task, response);
                    yield WorkflowState.NEEDS_CLARIFICATION;
                }
                case FAILED -> WorkflowState.FAILED;
            };
            exec.graph.updateState(task.id(), next);
        } catch (RuntimeException e) {
            audit(exec.projectId, task.id().value(), AuditLog.EventType.ERROR, "Task threw: " + e);
            try {
                exec.graph.updateState(task.id(), WorkflowState.FAILED);
            } catch (RuntimeException ignored) {
                // best-effort; the failure is already audited
            }
        } finally {
            checkpoint(exec); // checkpoint-after-step
        }
    }

    private void openGate(ProjectExecution exec, Task task, Agent.Response response) {
        String gateId = UUID.randomUUID().toString();
        String prompt = response.escalationReason()
                .orElse("Agent escalated task " + task.id() + " (" + response.outcome() + ")");
        exec.pendingGates.put(gateId, new PendingGate(gateId, GateType.CLARIFICATION, prompt));
        exec.paused = true;
        exec.state = WorkflowState.NEEDS_CLARIFICATION; // observable while the drive loop is paused
        audit(exec.projectId, task.id().value(), AuditLog.EventType.ESCALATION, "Gate opened: " + prompt);
    }

    private void reopenClarifiedTasks(ProjectExecution exec) {
        for (Task task : exec.graph.tasks()) {
            if (task.state() == WorkflowState.NEEDS_CLARIFICATION) {
                exec.graph.updateState(task.id(), WorkflowState.PENDING);
            }
        }
    }

    private void finish(ProjectExecution exec, WorkflowState terminal) {
        exec.state = terminal;
        audit(exec.projectId, null, AuditLog.EventType.STATE_CHANGE, "Project " + terminal);
        checkpoint(exec);
    }

    // ------------------------------------------------------------------------
    // Checkpointing
    // ------------------------------------------------------------------------

    private void checkpoint(ProjectExecution exec) {
        if (!(exec.graph instanceof InMemoryTaskGraph graph)) {
            return; // snapshot is only defined for the in-memory graph in this step
        }
        try {
            byte[] state = graph.snapshot().toBytes();
            long seq = exec.checkpointSeq.incrementAndGet();
            memoryStore.saveCheckpoint(new MemoryStore.Checkpoint(
                    UUID.randomUUID().toString(),
                    exec.projectId,
                    seq,
                    state,
                    Map.of("projectState", exec.state.name()),
                    Instant.now()));
        } catch (RuntimeException e) {
            audit(exec.projectId, null, AuditLog.EventType.ERROR, "Checkpoint failed: " + e);
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Block until a project reaches a settled state (terminal, blocked, or awaiting a gate).
     * Useful for the end-to-end demo and tests; not part of the {@link OrchestrationEngine} contract.
     */
    public WorkflowState awaitSettled(String projectId, Duration timeout) throws InterruptedException {
        ProjectExecution exec = require(projectId);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            WorkflowState s = exec.state;
            if (s == WorkflowState.DONE || s == WorkflowState.FAILED
                    || s == WorkflowState.BLOCKED || s == WorkflowState.NEEDS_CLARIFICATION) {
                return s;
            }
            Thread.sleep(5);
        }
        return exec.state;
    }

    private void awaitAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while running task batch", e);
            } catch (ExecutionException e) {
                // processOne never propagates business failures; this is an unexpected fault.
                throw new IllegalStateException("Task batch failed", e.getCause());
            }
        }
    }

    private ProjectExecution require(String projectId) {
        ProjectExecution exec = projects.get(projectId);
        if (exec == null) {
            throw new IllegalArgumentException("Unknown project: " + projectId);
        }
        return exec;
    }

    private void audit(String projectId, String taskId, AuditLog.EventType type, String summary) {
        auditLog.record(new AuditLog.AuditEvent(
                UUID.randomUUID().toString(), projectId, taskId, "engine", type, summary, Map.of(), Instant.now()));
    }

    /** Per-project mutable execution state held by the engine. */
    private static final class ProjectExecution {
        final String projectId;
        final TaskGraph graph;
        final AtomicLong checkpointSeq = new AtomicLong();
        final Map<String, PendingGate> pendingGates = new ConcurrentHashMap<>();
        volatile WorkflowState state = WorkflowState.PENDING;
        volatile boolean paused = false;
        volatile Future<?> driver;

        ProjectExecution(String projectId, TaskGraph graph) {
            this.projectId = projectId;
            this.graph = graph;
        }

        boolean hasPendingGates() {
            return !pendingGates.isEmpty();
        }
    }
}
