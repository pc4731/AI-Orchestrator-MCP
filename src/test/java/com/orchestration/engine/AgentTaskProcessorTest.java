package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentId;
import com.orchestration.agent.AgentRole;
import com.orchestration.agent.Capability;
import com.orchestration.artifact.JGitArtifactRepository;
import com.orchestration.audit.InMemoryAuditLog;
import com.orchestration.task.Task;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentTaskProcessorTest {

    @TempDir
    Path repoDir;

    /** Captures the request it receives so tests can inspect injected parameters. */
    private static Agent capturingAgent(AtomicReference<Agent.Request> sink) {
        return new Agent() {
            @Override public AgentId id() { return new AgentId("qa-1"); }
            @Override public AgentRole role() { return AgentRole.QA_ENGINEER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.RUN_TESTS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                sink.set(request);
                return new Response(Outcome.COMPLETED, Map.of(), List.of(),
                        Confidence.HIGH, List.of(), Optional.empty());
            }
        };
    }

    /** A stub agent that returns one code artifact. */
    private static Agent artifactAgent() {
        return new Agent() {
            @Override public AgentId id() { return new AgentId("agent-1"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.COMPLETED,
                        Map.of("summary", "done"),
                        List.of(new Artifact("src/A.java", "class A {}", "text/plain")),
                        Confidence.HIGH, List.of(), Optional.empty());
            }
        };
    }

    private static AgentFactory factoryReturning(Agent agent) {
        return new AgentFactory() {
            @Override public Agent create(AgentRole role) { return agent; }
            @Override public boolean supports(AgentRole role) { return true; }
            @Override public Set<AgentRole> supportedRoles() { return Set.of(agent.role()); }
        };
    }

    @Test
    void commitsArtifactsAndAuditsTheTask() {
        JGitArtifactRepository repo = new JGitArtifactRepository(repoDir);
        InMemoryAuditLog audit = new InMemoryAuditLog();
        AgentTaskProcessor processor = new AgentTaskProcessor(factoryReturning(artifactAgent()), repo, audit);

        Task task = new Task(new TaskId("t1"), "Implement", "code it", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());

        Agent.Response response = processor.process("p1", task);

        assertEquals(Agent.Outcome.COMPLETED, response.outcome());
        assertEquals("class A {}", repo.read("src/A.java").orElseThrow());
        assertFalse(audit.forTask("t1").isEmpty());
    }

    @Test
    void injectsWorkingDirAndDefaultTestCommandIntoTheRequest() {
        AtomicReference<Agent.Request> seen = new AtomicReference<>();
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(capturingAgent(seen)),
                new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                "/work/repo", List.of("npm", "test"));

        Task task = new Task(new TaskId("t1"), "QA", "", AgentRole.QA_ENGINEER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        processor.process("p1", task);

        assertEquals("/work/repo", seen.get().parameters().get("workingDir"));
        assertEquals(List.of("npm", "test"), seen.get().parameters().get("testCommand"));
    }

    @Test
    void taskMetadataOverridesTheDefaultTestCommand() {
        AtomicReference<Agent.Request> seen = new AtomicReference<>();
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(capturingAgent(seen)),
                new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                "/work/repo", List.of("./gradlew", "test"));

        Task task = new Task(new TaskId("t1"), "QA", "", AgentRole.QA_ENGINEER,
                WorkflowState.PENDING, List.of(), Map.of("testCommand", List.of("pytest")),
                Instant.now(), Instant.now());
        processor.process("p1", task);

        assertEquals(List.of("pytest"), seen.get().parameters().get("testCommand"));
    }
}
