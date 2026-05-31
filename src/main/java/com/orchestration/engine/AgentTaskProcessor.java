package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.artifact.ArtifactRepository;
import com.orchestration.audit.AuditLog;
import com.orchestration.task.Task;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The real {@link TaskProcessor}: routes a task to the right agent (via {@link AgentFactory}), runs
 * it, commits any produced artifacts to the Git-backed repository, and records the prompt/response
 * in the audit log. The engine's dispatch loop calls this for every ready task.
 *
 * <p>It injects two execution parameters into every agent request so QA runs against the real
 * generated code: {@code workingDir} (the Git-backed repo) and {@code testCommand} (the project's
 * default test command). A task's own metadata takes precedence, so the Team Lead can override the
 * test command per task (e.g. {@code [npm, test]} for a Node project).
 */
public class AgentTaskProcessor implements TaskProcessor {

    private final AgentFactory agentFactory;
    private final ArtifactRepository artifactRepository;
    private final AuditLog auditLog;
    private final String workingDir;
    private final List<String> defaultTestCommand;

    /** Convenience for tests: working dir "." and a Gradle test command. */
    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog) {
        this(agentFactory, artifactRepository, auditLog, ".", List.of("./gradlew", "test"));
    }

    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog,
                              String workingDir,
                              List<String> defaultTestCommand) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.workingDir = Objects.requireNonNull(workingDir, "workingDir");
        this.defaultTestCommand = List.copyOf(defaultTestCommand);
    }

    @Override
    public Agent.Response process(String projectId, Task task) {
        Agent agent = agentFactory.create(task.assignedRole());
        audit(projectId, task, agent, AuditLog.EventType.PROMPT, "Dispatching to " + agent.role());

        // Merge execution defaults under the task's own metadata (metadata wins).
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("workingDir", workingDir);
        parameters.put("testCommand", defaultTestCommand);
        parameters.putAll(task.metadata());

        Agent.Request request = new Agent.Request(task, task.description(), Map.of(), parameters);
        Agent.Context context = new Agent.Context(projectId, task.id().value(), Map.of());
        Agent.Response response = agent.handle(request, context);

        commitArtifacts(task, agent, response);
        audit(projectId, task, agent, AuditLog.EventType.RESPONSE,
                "outcome=" + response.outcome() + ", confidence=" + response.confidence()
                        + ", artifacts=" + response.artifacts().size());
        return response;
    }

    private void commitArtifacts(Task task, Agent agent, Agent.Response response) {
        if (response.artifacts().isEmpty()) {
            return;
        }
        List<ArtifactRepository.FileChange> changes = response.artifacts().stream()
                .map(a -> new ArtifactRepository.FileChange(a.path(), a.content()))
                .toList();
        artifactRepository.write(new ArtifactRepository.WriteRequest(
                task.id().value(), agent.role().name(),
                "[" + agent.role() + "] " + task.title(), changes));
    }

    private void audit(String projectId, Task task, Agent agent, AuditLog.EventType type, String summary) {
        auditLog.record(new AuditLog.AuditEvent(
                UUID.randomUUID().toString(), projectId, task.id().value(),
                agent.id().value(), type, summary, Map.of(), Instant.now()));
    }
}
