package com.orchestration.task;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A Directed Acyclic Graph of {@link Task}s with explicit dependencies — the workflow engine's
 * core data structure. Tasks with no unmet prerequisites can run in parallel; dependent tasks
 * run only after their prerequisites complete.
 *
 * <p><b>Invariant:</b> implementations MUST reject any {@link #addDependency} that would introduce
 * a cycle (throwing rather than silently accepting it), so the graph stays acyclic and
 * {@link #topologicalOrder()} is always well-defined.
 *
 * <p>The graph is the unit that gets checkpointed for resumption, so implementations must be
 * serialisable through the {@code MemoryStore.Checkpoint} mechanism.
 */
public interface TaskGraph {

    /** Add a task. Throws if a task with the same id already exists. */
    void addTask(Task task);

    /**
     * Declare that {@code dependent} requires {@code prerequisite} to finish first.
     *
     * @throws IllegalArgumentException if either id is unknown
     * @throws IllegalStateException    if the edge would introduce a cycle
     */
    void addDependency(TaskId dependent, TaskId prerequisite);

    Optional<Task> task(TaskId id);

    Collection<Task> tasks();

    /** Direct prerequisites of the given task. */
    Set<TaskId> dependencies(TaskId id);

    /** Tasks that directly depend on the given task. */
    Set<TaskId> dependents(TaskId id);

    /**
     * Tasks currently eligible to run: state {@code PENDING} and all prerequisites {@code DONE}.
     * The engine pulls from here to dispatch work (possibly several at once).
     */
    Set<Task> readyTasks();

    /**
     * Transition a task to a new state, delegating validation to the state machine.
     *
     * @return the updated task
     * @throws IllegalStateException if the transition is not permitted by {@link WorkflowState}
     */
    Task updateState(TaskId id, WorkflowState newState);

    /** True when every task is in a terminal state ({@code DONE} or {@code FAILED}). */
    boolean isComplete();

    /** A topological ordering of all tasks — used for deterministic scheduling and resume. */
    List<TaskId> topologicalOrder();
}
