package com.orchestration.task;

import com.orchestration.agent.AgentRole;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory {@link TaskGraph}: a DAG of tasks with adjacency maintained in both directions so that
 * dependency and dependent lookups are cheap.
 *
 * <p>The graph is the authority on edges: {@link #addDependency} updates the adjacency maps and keeps
 * each task's denormalised {@code dependsOn} list in sync. New tasks should therefore be added with
 * an empty {@code dependsOn} and have their edges declared via {@link #addDependency}.
 *
 * <p>All mutating and reading methods are guarded by a single intrinsic lock; the orchestration
 * engine dispatches tasks across virtual threads, so the graph must be safe to touch concurrently.
 */
public class InMemoryTaskGraph implements TaskGraph {

    private final Map<TaskId, Task> tasks = new LinkedHashMap<>();
    private final Map<TaskId, Set<TaskId>> dependencies = new HashMap<>(); // id -> its prerequisites
    private final Map<TaskId, Set<TaskId>> dependents = new HashMap<>();   // id -> tasks depending on it
    private final WorkflowStateMachine stateMachine;
    private final Object lock = new Object();

    public InMemoryTaskGraph() {
        this(new WorkflowStateMachine());
    }

    public InMemoryTaskGraph(WorkflowStateMachine stateMachine) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
    }

    @Override
    public void addTask(Task task) {
        Objects.requireNonNull(task, "task");
        synchronized (lock) {
            if (tasks.containsKey(task.id())) {
                throw new IllegalArgumentException("Task already exists: " + task.id());
            }
            tasks.put(task.id(), task);
            dependencies.computeIfAbsent(task.id(), k -> new LinkedHashSet<>());
            dependents.computeIfAbsent(task.id(), k -> new LinkedHashSet<>());
        }
    }

    @Override
    public void addDependency(TaskId dependent, TaskId prerequisite) {
        synchronized (lock) {
            addDependencyInternal(dependent, prerequisite);
        }
    }

    private void addDependencyInternal(TaskId dependent, TaskId prerequisite) {
        requireExists(dependent);
        requireExists(prerequisite);
        if (dependent.equals(prerequisite)) {
            throw new IllegalStateException("A task cannot depend on itself: " + dependent);
        }
        if (dependencies.get(dependent).contains(prerequisite)) {
            return; // idempotent
        }
        // Adding "dependent requires prerequisite" creates a path dependent -> prerequisite. That
        // closes a cycle iff prerequisite already (transitively) depends on dependent.
        if (dependsOnTransitively(prerequisite, dependent)) {
            throw new IllegalStateException(
                    "Dependency would introduce a cycle: " + dependent + " -> " + prerequisite);
        }
        dependencies.get(dependent).add(prerequisite);
        dependents.get(prerequisite).add(dependent);

        Task current = tasks.get(dependent);
        List<TaskId> updated = new ArrayList<>(current.dependsOn());
        updated.add(prerequisite);
        tasks.put(dependent, current.withDependsOn(updated));
    }

    /** DFS over dependency (prerequisite) edges from {@code start}; true if {@code target} is reachable. */
    private boolean dependsOnTransitively(TaskId start, TaskId target) {
        Deque<TaskId> stack = new ArrayDeque<>(dependencies.getOrDefault(start, Set.of()));
        Set<TaskId> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            TaskId current = stack.pop();
            if (current.equals(target)) {
                return true;
            }
            if (seen.add(current)) {
                stack.addAll(dependencies.getOrDefault(current, Set.of()));
            }
        }
        return false;
    }

    @Override
    public Optional<Task> task(TaskId id) {
        synchronized (lock) {
            return Optional.ofNullable(tasks.get(id));
        }
    }

    @Override
    public Collection<Task> tasks() {
        synchronized (lock) {
            return List.copyOf(tasks.values());
        }
    }

    @Override
    public Set<TaskId> dependencies(TaskId id) {
        synchronized (lock) {
            requireExists(id);
            return Set.copyOf(dependencies.get(id));
        }
    }

    @Override
    public Set<TaskId> dependents(TaskId id) {
        synchronized (lock) {
            requireExists(id);
            return Set.copyOf(dependents.get(id));
        }
    }

    @Override
    public Set<Task> readyTasks() {
        synchronized (lock) {
            Set<Task> ready = new LinkedHashSet<>();
            for (Task t : tasks.values()) {
                if (t.state() != WorkflowState.PENDING) {
                    continue;
                }
                boolean prerequisitesDone = dependencies.get(t.id()).stream()
                        .allMatch(dep -> tasks.get(dep).state() == WorkflowState.DONE);
                if (prerequisitesDone) {
                    ready.add(t);
                }
            }
            return ready;
        }
    }

    @Override
    public Task updateState(TaskId id, WorkflowState newState) {
        synchronized (lock) {
            Task current = tasks.get(id);
            if (current == null) {
                throw new IllegalArgumentException("Unknown task: " + id);
            }
            WorkflowState validated = stateMachine.transition(current.state(), newState);
            Task updated = current.withState(validated);
            tasks.put(id, updated);
            return updated;
        }
    }

    @Override
    public boolean isComplete() {
        synchronized (lock) {
            return !tasks.isEmpty()
                    && tasks.values().stream().allMatch(t -> t.state().isTerminal());
        }
    }

    @Override
    public List<TaskId> topologicalOrder() {
        synchronized (lock) {
            Map<TaskId, Integer> indegree = new HashMap<>();
            for (TaskId id : tasks.keySet()) {
                indegree.put(id, dependencies.get(id).size());
            }
            // Seed with zero-indegree nodes in insertion order for a deterministic ordering.
            Deque<TaskId> queue = new ArrayDeque<>();
            for (TaskId id : tasks.keySet()) {
                if (indegree.get(id) == 0) {
                    queue.add(id);
                }
            }
            List<TaskId> order = new ArrayList<>(tasks.size());
            while (!queue.isEmpty()) {
                TaskId node = queue.poll();
                order.add(node);
                for (TaskId dependent : dependents.get(node)) {
                    int remaining = indegree.merge(dependent, -1, Integer::sum);
                    if (remaining == 0) {
                        queue.add(dependent);
                    }
                }
            }
            if (order.size() != tasks.size()) {
                throw new IllegalStateException("Task graph contains a cycle");
            }
            return order;
        }
    }

    // ------------------------------------------------------------------------
    // Checkpoint support
    // ------------------------------------------------------------------------

    /** Capture a serialisable snapshot of the whole graph for checkpointing. */
    public GraphSnapshot snapshot() {
        synchronized (lock) {
            List<GraphSnapshot.TaskNode> nodes = new ArrayList<>(tasks.size());
            for (Task t : tasks.values()) {
                nodes.add(new GraphSnapshot.TaskNode(
                        t.id().value(),
                        t.title(),
                        t.description(),
                        t.assignedRole() == null ? null : t.assignedRole().name(),
                        t.state().name(),
                        t.dependsOn().stream().map(TaskId::value).toList(),
                        t.createdAt().toEpochMilli()));
            }
            return new GraphSnapshot(nodes);
        }
    }

    /** Rebuild a graph from a snapshot (used on resume). */
    public static InMemoryTaskGraph fromSnapshot(GraphSnapshot snapshot) {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        for (GraphSnapshot.TaskNode n : snapshot.nodes()) {
            Task task = new Task(
                    new TaskId(n.id()),
                    n.title(),
                    n.description(),
                    n.assignedRole() == null ? null : AgentRole.valueOf(n.assignedRole()),
                    WorkflowState.valueOf(n.state()),
                    List.of(),
                    Map.of(),
                    Instant.ofEpochMilli(n.createdAtEpochMilli()),
                    Instant.now());
            graph.addTask(task);
        }
        for (GraphSnapshot.TaskNode n : snapshot.nodes()) {
            TaskId dependent = new TaskId(n.id());
            for (String prereq : n.dependsOn()) {
                graph.addDependency(dependent, new TaskId(prereq));
            }
        }
        return graph;
    }

    private void requireExists(TaskId id) {
        if (!tasks.containsKey(id)) {
            throw new IllegalArgumentException("Unknown task: " + id);
        }
    }
}
