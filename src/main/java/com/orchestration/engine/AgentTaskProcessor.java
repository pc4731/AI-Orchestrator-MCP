package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentRole;
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
 * <p>Two quality mechanisms wrap each worker:
 * <ul>
 *   <li><b>Prompt Engineer pre-step</b> — before a building/design/review agent runs, the
 *       PROMPT_ENGINEER (if configured) refines the task into a crisp prompt with explicit
 *       acceptance criteria, which is passed to the worker as grounding.</li>
 *   <li><b>Rework loop</b> — when a worker returns {@code NEEDS_REVIEW}, the task is re-run with the
 *       reviewer's feedback up to {@code maxReworkAttempts} times, so the team iterates toward a
 *       result that meets the criteria instead of accepting a weak first pass. Only the final
 *       outcome is returned to the engine.</li>
 * </ul>
 *
 * <p>It also injects {@code workingDir} (the Git-backed repo) and {@code testCommand} into every
 * request so QA runs against the real generated code; a task's own metadata takes precedence.
 */
public class AgentTaskProcessor implements TaskProcessor {

    private final AgentFactory agentFactory;
    private final ArtifactRepository artifactRepository;
    private final AuditLog auditLog;
    private final String workingDir;
    private final List<String> defaultTestCommand;
    private final int maxReworkAttempts;

    /** Convenience for tests: working dir ".", Gradle test command, 2 rework attempts. */
    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog) {
        this(agentFactory, artifactRepository, auditLog, ".", List.of("./gradlew", "test"), 2);
    }

    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog,
                              String workingDir,
                              List<String> defaultTestCommand,
                              int maxReworkAttempts) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.workingDir = Objects.requireNonNull(workingDir, "workingDir");
        this.defaultTestCommand = List.copyOf(defaultTestCommand);
        this.maxReworkAttempts = Math.max(0, maxReworkAttempts);
    }

    @Override
    public Agent.Response process(String projectId, Task task) {
        Agent agent = agentFactory.create(task.assignedRole());

        // Prompt Engineer pre-step: refine the task prompt for worker roles.
        String refinedPrompt = refinePrompt(projectId, task);

        Map<String, Object> baseParams = new LinkedHashMap<>();
        baseParams.put("workingDir", workingDir);
        baseParams.put("testCommand", defaultTestCommand);
        baseParams.putAll(task.metadata());

        Agent.Response response = null;
        String reviewFeedback = null;

        // Initial run plus up to maxReworkAttempts re-runs while the work needs review.
        for (int attempt = 0; attempt <= maxReworkAttempts; attempt++) {
            Map<String, String> grounding = new LinkedHashMap<>();
            if (!refinedPrompt.isBlank()) {
                grounding.put("refinedPrompt", refinedPrompt);
            }
            if (reviewFeedback != null) {
                grounding.put("reviewFeedback", reviewFeedback);
            }
            String instructions = task.description()
                    + (attempt > 0 ? "\n\nThis is rework attempt " + attempt
                        + ". Address the reviewFeedback and meet the acceptance criteria." : "");

            // What prompt is this agent being given? (refined prompt if any, else the instructions.)
            String sharedPrompt = !refinedPrompt.isBlank() ? refinedPrompt : instructions;
            String collaborator = attempt > 0 ? "reviewer (rework)"
                    : (!refinedPrompt.isBlank() ? "PROMPT_ENGINEER" : "TEAM_LEAD");
            audit(projectId, task, agent, AuditLog.EventType.PROMPT,
                    (attempt == 0 ? "Dispatching to " : "Rework #" + attempt + " for ") + agent.role(),
                    Map.of("role", agent.role().name(),
                            "collaborator", collaborator,
                            "prompt", sharedPrompt));

            Agent.Request request = new Agent.Request(task, instructions, grounding, baseParams);
            Agent.Context context = new Agent.Context(projectId, task.id().value(), Map.of());
            response = agent.handle(request, context);
            commitArtifacts(task, agent, response, attempt);

            audit(projectId, task, agent, AuditLog.EventType.RESPONSE,
                    "outcome=" + response.outcome() + ", confidence=" + response.confidence()
                            + ", artifacts=" + response.artifacts().size()
                            + (attempt > 0 ? " (rework #" + attempt + ")" : ""),
                    Map.of("role", agent.role().name(),
                            "outcome", response.outcome().name(),
                            "detail", response.escalationReason().orElse(
                                    summarize(response.structuredOutput()))));

            if (response.outcome() != Agent.Outcome.NEEDS_REVIEW || attempt == maxReworkAttempts) {
                break;
            }
            reviewFeedback = response.escalationReason()
                    .orElse("The previous result did not meet the acceptance criteria; improve it.");
        }
        return response;
    }

    /** Run the Prompt Engineer to sharpen the prompt for worker roles; empty for non-worker roles
     *  or when PROMPT_ENGINEER is not configured. Best-effort — never fails the task. */
    private String refinePrompt(String projectId, Task task) {
        if (!needsRefinement(task.assignedRole()) || !agentFactory.supports(AgentRole.PROMPT_ENGINEER)) {
            return "";
        }
        try {
            Agent pe = agentFactory.create(AgentRole.PROMPT_ENGINEER);
            String ask = "Target role: " + task.assignedRole() + "\nTask: " + task.title()
                    + "\nDetails: " + task.description();
            auditLog.record(new AuditLog.AuditEvent(
                    UUID.randomUUID().toString(), projectId, task.id().value(), pe.id().value(),
                    AuditLog.EventType.PROMPT, "PROMPT_ENGINEER refining prompt for " + task.assignedRole(),
                    Map.of("role", "PROMPT_ENGINEER", "collaborator", task.assignedRole().name(),
                            "prompt", ask), Instant.now()));
            Agent.Request request = new Agent.Request(task, ask, Map.of(), Map.of());
            Agent.Response r = pe.handle(request, new Agent.Context(projectId, task.id().value(), Map.of()));
            Object refined = r.structuredOutput().get("refinedPrompt");
            String refinedText = refined != null ? refined.toString() : "";
            auditLog.record(new AuditLog.AuditEvent(
                    UUID.randomUUID().toString(), projectId, task.id().value(), pe.id().value(),
                    AuditLog.EventType.RESPONSE, "PROMPT_ENGINEER produced a refined prompt",
                    Map.of("role", "PROMPT_ENGINEER", "detail",
                            refinedText.isBlank() ? "(no refinement)" : summarizeText(refinedText)),
                    Instant.now()));
            return refinedText;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private boolean needsRefinement(AgentRole role) {
        return switch (role) {
            case PROMPT_ENGINEER, BUSINESS_ANALYST, TEAM_LEAD -> false; // don't refine the meta-roles
            default -> true;
        };
    }

    private void commitArtifacts(Task task, Agent agent, Agent.Response response, int attempt) {
        if (response.artifacts().isEmpty()) {
            return;
        }
        List<ArtifactRepository.FileChange> changes = response.artifacts().stream()
                .map(a -> new ArtifactRepository.FileChange(a.path(), a.content()))
                .toList();
        String suffix = attempt > 0 ? " (rework #" + attempt + ")" : "";
        artifactRepository.write(new ArtifactRepository.WriteRequest(
                task.id().value(), agent.role().name(),
                "[" + agent.role() + "] " + task.title() + suffix, changes));
    }

    private void audit(String projectId, Task task, Agent agent, AuditLog.EventType type, String summary) {
        audit(projectId, task, agent, type, summary, Map.of());
    }

    private void audit(String projectId, Task task, Agent agent, AuditLog.EventType type,
                       String summary, Map<String, Object> details) {
        auditLog.record(new AuditLog.AuditEvent(
                UUID.randomUUID().toString(), projectId, task.id().value(),
                agent.id().value(), type, summary, details, Instant.now()));
    }

    private static String summarize(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        Object summary = output.getOrDefault("summary", output.getOrDefault("specification", ""));
        return summarizeText(summary.toString());
    }

    private static String summarizeText(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= 280 ? trimmed : trimmed.substring(0, 280) + "…";
    }
}

