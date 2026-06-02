package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentRole;
import com.orchestration.artifact.ArtifactRepository;
import com.orchestration.audit.AuditLog;
import com.orchestration.knowledge.ProjectKnowledgeStore;
import com.orchestration.task.Task;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 *   <li><b>Collaboration hand-offs</b> — when a task completes, its output is recorded on a shared
 *       board keyed by task id. Before a downstream task runs, the outputs of its completed
 *       dependencies (the architect's spec, the designer's tokens, …) are injected as grounding, so
 *       agents genuinely build on each other's work instead of running in isolation.</li>
 * </ul>
 *
 * <p>It also injects {@code workingDir} (the Git-backed repo) and {@code testCommand} into every
 * request so QA runs against the real generated code; a task's own metadata takes precedence.
 */
public class AgentTaskProcessor implements TaskProcessor {

    /** One agent's completed output, handed to downstream agents that depend on its task. */
    private record Handoff(String role, String summary) {}

    private final AgentFactory agentFactory;
    private final ArtifactRepository artifactRepository;
    private final AuditLog auditLog;
    private final String workingDir;
    private final List<String> defaultTestCommand;
    private final int maxReworkAttempts;
    private final ProjectKnowledgeStore knowledgeStore; // optional; null disables the project brain

    // Completed task outputs, keyed by task id, shared across the project's tasks.
    private final Map<String, Handoff> handoffs = new ConcurrentHashMap<>();

    /** Convenience for tests: working dir ".", Gradle test command, 2 rework attempts, no knowledge. */
    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog) {
        this(agentFactory, artifactRepository, auditLog, ".", List.of("./gradlew", "test"), 2, null);
    }

    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog,
                              String workingDir,
                              List<String> defaultTestCommand,
                              int maxReworkAttempts) {
        this(agentFactory, artifactRepository, auditLog, workingDir, defaultTestCommand,
                maxReworkAttempts, null);
    }

    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog,
                              String workingDir,
                              List<String> defaultTestCommand,
                              int maxReworkAttempts,
                              ProjectKnowledgeStore knowledgeStore) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.workingDir = Objects.requireNonNull(workingDir, "workingDir");
        this.defaultTestCommand = List.copyOf(defaultTestCommand);
        this.maxReworkAttempts = Math.max(0, maxReworkAttempts);
        this.knowledgeStore = knowledgeStore;
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

        // Collaboration: gather the outputs of this task's completed dependencies as grounding.
        Map<String, String> upstream = upstreamHandoffs(task);

        // QA verification gets a build-fix loop (re-run a developer on failure) instead of the
        // generic rework loop, since re-running QA on a red build would change nothing.
        Agent.Response response = agent.role() == AgentRole.QA_ENGINEER
                ? verifyWithBuildFix(projectId, task, agent, baseParams, upstream)
                : runWithRework(projectId, task, agent, refinedPrompt, baseParams, upstream);

        // Publish this task's output to the collaboration board for downstream agents.
        recordHandoff(task, agent, response);
        // The Knowledge Curator's brief is committed as the project knowledge file for future sessions.
        persistKnowledge(projectId, task, agent, response);
        return response;
    }

    /** Initial run plus up to {@code maxReworkAttempts} re-runs of the same agent while it needs
     *  review, feeding the reviewer's feedback back as grounding. Only the final outcome is returned. */
    private Agent.Response runWithRework(String projectId, Task task, Agent agent,
                                         String refinedPrompt, Map<String, Object> baseParams,
                                         Map<String, String> upstream) {
        Agent.Response response = null;
        String reviewFeedback = null;

        // Initial run plus up to maxReworkAttempts re-runs while the work needs review.
        for (int attempt = 0; attempt <= maxReworkAttempts; attempt++) {
            Map<String, String> grounding = new LinkedHashMap<>(upstream);
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
            // Who is this agent building on? Upstream dependency roles, else the pre-step collaborator.
            String collaborator = attempt > 0 ? "reviewer (rework)"
                    : upstream.isEmpty()
                        ? (!refinedPrompt.isBlank() ? "PROMPT_ENGINEER" : "TEAM_LEAD")
                        : String.join(", ", upstream.keySet()).replace("from_", "");
            String summary = (attempt == 0 ? "Dispatching to " : "Rework #" + attempt + " for ")
                    + agent.role()
                    + (upstream.isEmpty() ? "" : " (building on " + collaborator + ")");
            audit(projectId, task, agent, AuditLog.EventType.PROMPT, summary,
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

    /**
     * Run QA, and if the build/tests fail (NEEDS_REVIEW), re-dispatch a developer to fix it for real
     * with the failure output as grounding, then re-verify — up to {@code maxReworkAttempts} times.
     * This is what enforces "a broken build is never accepted": either it ends green (COMPLETED) or,
     * if it can't be fixed, it stays NEEDS_REVIEW so the engine never marks the project DONE.
     */
    private Agent.Response verifyWithBuildFix(String projectId, Task task, Agent qa,
                                              Map<String, Object> baseParams,
                                              Map<String, String> upstream) {
        Agent.Response response = dispatch(projectId, task, qa, task.description(), upstream,
                baseParams, "developers (verifying their build)", "Verifying build for " + qa.role(), 0);

        for (int attempt = 1; attempt <= maxReworkAttempts; attempt++) {
            if (response.outcome() != Agent.Outcome.NEEDS_REVIEW) {
                break; // build is green (or an outcome a developer can't fix)
            }
            AgentRole devRole = developerToFix(task);
            if (!agentFactory.supports(devRole)) {
                break; // no developer available to repair the build
            }
            String failure = buildFailureDetail(response);
            Agent developer = agentFactory.create(devRole);
            Task fixTask = new Task(TaskId.random(), "Fix failing build", task.description(), devRole,
                    WorkflowState.IN_PROGRESS, List.of(), Map.of(), Instant.now(), Instant.now());
            String fixInstructions = "The project does NOT build / its tests FAIL. Fix the code so it "
                    + "compiles and every test passes; return the corrected files as artifacts.\n\n"
                    + "Build failure output:\n" + failure;
            Map<String, String> fixGrounding = new LinkedHashMap<>(upstream);
            fixGrounding.put("buildFailure", failure);
            dispatch(projectId, fixTask, developer, fixInstructions, fixGrounding, baseParams,
                    qa.role().name(), "Build-fix #" + attempt + " by " + devRole, attempt);

            response = dispatch(projectId, task, qa, task.description(), upstream, baseParams,
                    devRole.name(), "Re-verifying build (attempt " + attempt + ")", attempt);
        }
        return response;
    }

    /** One agent invocation: audit the prompt, run it, commit any artifacts, audit the response. */
    private Agent.Response dispatch(String projectId, Task task, Agent agent, String instructions,
                                    Map<String, String> grounding, Map<String, Object> baseParams,
                                    String collaborator, String summary, int attempt) {
        audit(projectId, task, agent, AuditLog.EventType.PROMPT, summary,
                Map.of("role", agent.role().name(), "collaborator", collaborator, "prompt", instructions));
        Agent.Request request = new Agent.Request(task, instructions, grounding, baseParams);
        Agent.Context context = new Agent.Context(projectId, task.id().value(), Map.of());
        Agent.Response response = agent.handle(request, context);
        commitArtifacts(task, agent, response, attempt);
        audit(projectId, task, agent, AuditLog.EventType.RESPONSE,
                "outcome=" + response.outcome() + ", confidence=" + response.confidence()
                        + ", artifacts=" + response.artifacts().size(),
                Map.of("role", agent.role().name(), "outcome", response.outcome().name(),
                        "detail", response.escalationReason().orElse(summarize(response.structuredOutput()))));
        return response;
    }

    /** The developer role responsible for the code under test — derived from the QA task's
     *  completed dependencies, defaulting to the backend developer. */
    private AgentRole developerToFix(Task task) {
        for (TaskId dep : task.dependsOn()) {
            Handoff h = handoffs.get(dep.value());
            if (h != null && h.role().endsWith("_DEVELOPER")) {
                try {
                    return AgentRole.valueOf(h.role());
                } catch (IllegalArgumentException ignored) {
                    // fall through to the default
                }
            }
        }
        return AgentRole.BACKEND_DEVELOPER;
    }

    /** Extract a compact, developer-actionable failure description from a QA response. */
    private static String buildFailureDetail(Agent.Response qaResponse) {
        StringBuilder sb = new StringBuilder();
        qaResponse.escalationReason().ifPresent(r -> sb.append(r).append('\n'));
        Object stderr = qaResponse.structuredOutput().get("stderr");
        Object stdout = qaResponse.structuredOutput().get("stdout");
        if (stderr != null && !stderr.toString().isBlank()) {
            sb.append(stderr);
        } else if (stdout != null) {
            sb.append(stdout);
        }
        String text = sb.toString().strip();
        return text.length() <= 4000 ? text : text.substring(0, 4000) + "…[truncated]";
    }

    /** Collect the outputs of this task's completed dependencies, keyed for prompt grounding. */
    private Map<String, String> upstreamHandoffs(Task task) {
        Map<String, String> upstream = new LinkedHashMap<>();
        for (TaskId dep : task.dependsOn()) {
            Handoff h = handoffs.get(dep.value());
            if (h != null && !h.summary().isBlank()) {
                // e.g. "from_BACKEND_ARCHITECT" -> the architect's spec/instructions.
                upstream.put("from_" + h.role(), h.summary());
            }
        }
        return upstream;
    }

    /** Record a task's output so dependents can build on it; audit the hand-off for the live view. */
    private void recordHandoff(Task task, Agent agent, Agent.Response response) {
        if (response == null) {
            return;
        }
        String summary = response.escalationReason().isPresent()
                ? "" : summarize(response.structuredOutput());
        // Prefer concrete instructions/spec/tokens fields if the agent produced them.
        for (String key : List.of("instructions", "specification", "tokens", "schema", "summary")) {
            Object v = response.structuredOutput().get(key);
            if (v != null && !v.toString().isBlank()) {
                summary = summarizeText(v.toString());
                break;
            }
        }
        handoffs.put(task.id().value(), new Handoff(agent.role().name(), summary));
    }

    /** When the Knowledge Curator finishes, commit its brief as the project knowledge file. */
    private void persistKnowledge(String projectId, Task task, Agent agent, Agent.Response response) {
        if (knowledgeStore == null || agent.role() != AgentRole.KNOWLEDGE_CURATOR || response == null) {
            return;
        }
        Object knowledge = response.structuredOutput().getOrDefault("knowledge",
                response.structuredOutput().get("summary"));
        if (knowledge == null || knowledge.toString().isBlank()) {
            return;
        }
        try {
            knowledgeStore.save(knowledge.toString(), task.id().value(), agent.role().name());
            audit(projectId, task, agent, AuditLog.EventType.RESPONSE,
                    "KNOWLEDGE_CURATOR committed the project knowledge brief",
                    Map.of("role", AgentRole.KNOWLEDGE_CURATOR.name(),
                            "detail", "project knowledge file updated"));
        } catch (RuntimeException e) {
            audit(projectId, task, agent, AuditLog.EventType.ERROR,
                    "Failed to persist project knowledge: " + e, Map.of());
        }
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
            // don't refine the meta-roles (planning/research/QA verification/knowledge capture)
            case PROMPT_ENGINEER, BUSINESS_ANALYST, MARKET_RESEARCHER, TEAM_LEAD, QA_ENGINEER,
                 KNOWLEDGE_CURATOR -> false;
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

