package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.artifact.ArtifactRepository;
import com.orchestration.audit.AuditLog;
import com.orchestration.task.Task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The real {@link TaskProcessor}: routes a task to the right agent (via {@link AgentFactory}), runs
 * it, commits any produced artifacts to the Git-backed repository, and records the prompt/response
 * in the audit log. The engine's dispatch loop calls this for every ready task.
 */
public class AgentTaskProcessor implements TaskProcessor {

    private final AgentFactory agentFactory;
    private final ArtifactRepository artifactRepository;
    private final AuditLog auditLog;

    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    @Override
    public Agent.Response process(String projectId, Task task) {
        Agent agent = agentFactory.create(task.assignedRole());
        audit(projectId, task, agent, AuditLog.EventType.PROMPT, "Dispatching to " + agent.role());

        Agent.Request request = new Agent.Request(task, task.description(), Map.of(), task.metadata());
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
