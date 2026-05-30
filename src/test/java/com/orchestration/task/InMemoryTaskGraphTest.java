package com.orchestration.task;

import com.orchestration.agent.AgentRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTaskGraphTest {

    private static Task pendingTask(String id) {
        return new Task(new TaskId(id), id, "desc", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
    }

    @Test
    void addsAndRetrievesTask() {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        graph.addTask(pendingTask("a"));
        assertTrue(graph.task(new TaskId("a")).isPresent());
        assertEquals(1, graph.tasks().size());
    }

    @Test
    void rejectsDuplicateTask() {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        graph.addTask(pendingTask("a"));
        assertThrows(IllegalArgumentException.class, () -> graph.addTask(pendingTask("a")));
    }

    @Test
    void addDependencyTracksBothDirectionsAndSyncsTask() {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        graph.addTask(pendingTask("a"));
        graph.addTask(pendingTask("b"));
        graph.addDependency(new TaskId("b"), new TaskId("a")); // b depends on a

        assertEquals(Set.of(new TaskId("a")), graph.dependencies(new TaskId("b")));
        assertEquals(Set.of(new TaskId("b")), graph.dependents(new TaskId("a")));
        assertEquals(List.of(new TaskId("a")), graph.task(new TaskId("b")).orElseThrow().dependsOn());
    }

    @Test
    void addDependencyToUnknownTaskThrows() {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        graph.addTask(pendingTask("a"));
        assertThrows(IllegalArgumentException.class,
                () -> graph.addDependency(new TaskId("a"), new TaskId("missing")));
    }

    @Test
    void rejectsSelfDependency() {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        graph.addTask(pendingTask("a"));
        assertThrows(IllegalStateException.class,
                () -> graph.addDependency(new TaskId("a"), new TaskId("a")));
    }

    @Test
    void detectsCycle() {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        graph.addTask(pendingTask("a"));
        graph.addTask(pendingTask("b"));
        graph.addTask(pendingTask("c"));
        graph.addDependency(new TaskId("b"), new TaskId("a")); // b -> a
        graph.addDependency(new TaskId("c"), new TaskId("b")); // c -> b
        // a -> c would close the loop a -> c -> b -> a
        assertThrows(IllegalStateException.class,
                () -> graph.addDependency(new TaskId("a"), new TaskId("c")));
    }

    @Test
    void readyTasksRequireAllPrerequisitesDone() {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        graph.addTask(pendingTask("a"));
        graph.addTask(pendingTask("b"));
        graph.addDependency(new TaskId("b"), new TaskId("a"));

        assertEquals(Set.of(new TaskId("a")),
                graph.readyTasks().stream().map(Task::id).collect(java.util.stream.Collectors.toSet()));

        graph.updateState(new TaskId("a"), WorkflowState.IN_PROGRESS);
        graph.updateState(new TaskId("a"), WorkflowState.DONE);

        assertEquals(Set.of(new TaskId("b")),
                graph.readyTasks().stream().map(Task::id).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void updateStateEnforcesStateMachine() {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        graph.addTask(pendingTask("a"));
        assertThrows(IllegalStateException.class,
                () -> graph.updateState(new TaskId("a"), WorkflowState.DONE)); // PENDING -> DONE illegal
    }

    @Test
    void isCompleteOnlyWhenAllTasksTerminal() {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        graph.addTask(pendingTask("a"));
        assertFalse(graph.isComplete());
        graph.updateState(new TaskId("a"), WorkflowState.IN_PROGRESS);
        graph.updateState(new TaskId("a"), WorkflowState.DONE);
        assertTrue(graph.isComplete());
    }

    @Test
    void topologicalOrderRespectsDependencies() {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        graph.addTask(pendingTask("a"));
        graph.addTask(pendingTask("b"));
        graph.addTask(pendingTask("c"));
        graph.addDependency(new TaskId("b"), new TaskId("a"));
        graph.addDependency(new TaskId("c"), new TaskId("b"));

        List<TaskId> order = graph.topologicalOrder();
        assertTrue(order.indexOf(new TaskId("a")) < order.indexOf(new TaskId("b")));
        assertTrue(order.indexOf(new TaskId("b")) < order.indexOf(new TaskId("c")));
    }

    @Test
    void snapshotRoundTripsStatesAndEdges() {
        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        graph.addTask(pendingTask("a"));
        graph.addTask(pendingTask("b"));
        graph.addDependency(new TaskId("b"), new TaskId("a"));
        graph.updateState(new TaskId("a"), WorkflowState.IN_PROGRESS);
        graph.updateState(new TaskId("a"), WorkflowState.DONE);

        InMemoryTaskGraph restored = InMemoryTaskGraph.fromSnapshot(graph.snapshot());

        assertEquals(WorkflowState.DONE, restored.task(new TaskId("a")).orElseThrow().state());
        assertEquals(WorkflowState.PENDING, restored.task(new TaskId("b")).orElseThrow().state());
        assertEquals(Set.of(new TaskId("a")), restored.dependencies(new TaskId("b")));
    }
}
