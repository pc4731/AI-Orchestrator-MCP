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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final ClarificationGateway clarifier; // optional; lets a blocked worker ask the user

    // Completed task outputs per project: projectId -> (taskId -> Handoff). Scoped per project and
    // evicted on completion (onProjectComplete) so a long-running server doesn't accumulate the
    // hand-offs of every project it ever ran.
    private final Map<String, Map<String, Handoff>> handoffs = new ConcurrentHashMap<>();

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
        this(agentFactory, artifactRepository, auditLog, workingDir, defaultTestCommand,
                maxReworkAttempts, knowledgeStore, null);
    }

    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog,
                              String workingDir,
                              List<String> defaultTestCommand,
                              int maxReworkAttempts,
                              ProjectKnowledgeStore knowledgeStore,
                              ClarificationGateway clarifier) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.workingDir = Objects.requireNonNull(workingDir, "workingDir");
        this.defaultTestCommand = List.copyOf(defaultTestCommand);
        this.maxReworkAttempts = Math.max(0, maxReworkAttempts);
        this.knowledgeStore = knowledgeStore;
        this.clarifier = clarifier;
    }

    @Override
    public Agent.Response process(String projectId, Task task) {
        Agent agent = agentFactory.create(task.assignedRole());

        Map<String, Object> baseParams = new LinkedHashMap<>();
        baseParams.put("workingDir", workingDir);
        baseParams.put("testCommand", defaultTestCommand);
        baseParams.putAll(task.metadata());

        // Collaboration: gather the outputs of this task's completed dependencies as grounding.
        Map<String, String> upstream = upstreamHandoffs(projectId, task);
        // Reviewers and QA review REAL code, not summaries: hand them the actual committed file
        // contents so findings are grounded in what was produced, not in developers' descriptions.
        if (needsFileAccess(task.assignedRole())) {
            injectProjectFiles(upstream);
        }

        // Prompt Engineer pre-step — only for worker roles whose task is under-specified, and given
        // the upstream artifacts so it sharpens with real context instead of paraphrasing blind.
        String refinedPrompt = refinePrompt(projectId, task, upstream);

        Agent.Response response = runTask(projectId, task, agent, refinedPrompt, baseParams, upstream);

        // Execution-time clarification: if the agent is blocked for missing info and a gateway is
        // available, ask the user and re-run with their answers — so a mid-build question is resolved
        // instead of leaving the project stuck awaiting a gate that nothing resolves.
        response = resolveIfBlocked(projectId, task, agent, refinedPrompt, baseParams, upstream, response);

        // Publish this task's output to the collaboration board for downstream agents.
        recordHandoff(projectId, task, agent, response);
        // The Knowledge Curator's brief is committed as the project knowledge file for future sessions.
        persistKnowledge(projectId, task, agent, response);
        return response;
    }

    /** Dispatch a task by role: QA gets the build-fix loop, reviewers get the developer-fix path,
     *  every other role the generic rework loop. */
    private Agent.Response runTask(String projectId, Task task, Agent agent, String refinedPrompt,
                                   Map<String, Object> baseParams, Map<String, String> upstream) {
        return switch (agent.role()) {
            // QA verification gets a build-fix loop (re-run a developer on failure) instead of the
            // generic rework loop, since re-running QA on a red build would change nothing.
            case QA_ENGINEER -> verifyWithBuildFix(projectId, task, agent, baseParams, upstream);
            // Reviewers finish their review (COMPLETED with findings); a developer is dispatched to
            // FIX the findings rather than re-running the reviewer as pointless "rework".
            case CODE_REVIEWER, SECURITY_REVIEWER ->
                    reviewWithDeveloperFix(projectId, task, agent, refinedPrompt, baseParams, upstream);
            default -> runWithRework(projectId, task, agent, refinedPrompt, baseParams, upstream);
        };
    }

    /**
     * Run a reviewer once. If it flags blocking issues (NEEDS_REVIEW), the review is still <i>done</i>
     * — so we return COMPLETED with its findings — and the fix is handed to a developer: either the
     * reviewer already supplied corrected artifacts (committed on its own dispatch), or we dispatch a
     * developer with the findings as grounding. This replaces re-running the same reviewer as rework.
     */
    private Agent.Response reviewWithDeveloperFix(String projectId, Task task, Agent reviewer,
                                                  String refinedPrompt, Map<String, Object> baseParams,
                                                  Map<String, String> upstream) {
        Map<String, String> grounding = new LinkedHashMap<>(upstream);
        if (!refinedPrompt.isBlank()) {
            grounding.put("refinedPrompt", refinedPrompt);
        }
        Agent.Response review = dispatch(projectId, task, reviewer, task.description(), grounding,
                baseParams, "developers (reviewing their work)", "Reviewing for " + reviewer.role(), 0);
        if (review.outcome() != Agent.Outcome.NEEDS_REVIEW) {
            return review; // no blocking findings — nothing to fix
        }
        // The reviewer found blocking issues but its job is complete. If it didn't already supply a
        // corrected artifact, dispatch a developer to apply the fixes.
        AgentRole devRole = developerToFix(projectId, task);
        if (review.artifacts().isEmpty() && agentFactory.supports(devRole)) {
            String findings = handoffText(reviewFindings(review));
            Agent developer = agentFactory.create(devRole);
            Task fixTask = new Task(TaskId.random(), "Fix review findings", task.description(), devRole,
                    WorkflowState.IN_PROGRESS, List.of(), Map.of(), Instant.now(), Instant.now());
            String fixInstructions = "A reviewer (" + reviewer.role() + ") found issues that must be "
                    + "fixed. Apply the fixes and return the corrected files as artifacts.\n\nFindings:\n"
                    + findings;
            Map<String, String> fixGrounding = new LinkedHashMap<>(upstream);
            fixGrounding.put("reviewFindings", findings);
            dispatch(projectId, fixTask, developer, fixInstructions, fixGrounding, baseParams,
                    reviewer.role().name(), "Fixing " + reviewer.role() + " findings by " + devRole, 1);
        }
        // The review is complete; the fix has been dispatched/applied. Don't re-run the reviewer.
        return new Agent.Response(Agent.Outcome.COMPLETED, review.structuredOutput(), review.artifacts(),
                review.confidence(), review.assumptions(), Optional.empty());
    }

    /** Flatten a reviewer's findings (output.findings + escalationReason) into developer-actionable text. */
    private static String reviewFindings(Agent.Response review) {
        StringBuilder sb = new StringBuilder();
        review.escalationReason().ifPresent(r -> sb.append(r).append('\n'));
        Object findings = review.structuredOutput().get("findings");
        if (findings != null) {
            sb.append(findings);
        } else {
            sb.append(String.valueOf(review.structuredOutput()));
        }
        return sb.toString().strip();
    }

    /**
     * When an agent reports it lacks information (INSUFFICIENT_INFORMATION / ESCALATE) and a
     * {@link ClarificationGateway} is wired, relay its questions to the user and re-run the task with
     * the answers as grounding, up to {@code maxReworkAttempts} times. Without a gateway, or if the
     * user can't answer, the original blocked response is returned unchanged.
     */
    private Agent.Response resolveIfBlocked(String projectId, Task task, Agent agent,
                                            String refinedPrompt, Map<String, Object> baseParams,
                                            Map<String, String> upstream, Agent.Response response) {
        if (clarifier == null) {
            return response;
        }
        Map<String, String> augmented = new LinkedHashMap<>(upstream);
        StringBuilder answered = new StringBuilder();
        for (int round = 1; round <= maxReworkAttempts && isBlocked(response); round++) {
            List<String> questions = blockingQuestions(response);
            if (questions.isEmpty()) {
                break;
            }
            Optional<String> answers = safeAsk(projectId, questions, task);
            if (answers.isEmpty()) {
                break; // no human available / declined: leave the task blocked
            }
            answered.append("Q: ").append(String.join(" | ", questions))
                    .append("\nA: ").append(answers.get()).append("\n\n");
            augmented.put("userClarification", answered.toString().strip());
            audit(projectId, task, agent, AuditLog.EventType.PROMPT,
                    "Clarified with the user; re-running " + agent.role(),
                    Map.of("role", agent.role().name(), "collaborator", "user",
                            "prompt", String.join(" | ", questions)));
            response = runTask(projectId, task, agent, refinedPrompt, baseParams, augmented);
        }
        return response;
    }

    private static boolean isBlocked(Agent.Response r) {
        return r.outcome() == Agent.Outcome.INSUFFICIENT_INFORMATION
                || r.outcome() == Agent.Outcome.ESCALATE;
    }

    /** The questions a blocked agent wants answered (output.questions, else its escalationReason). */
    private List<String> blockingQuestions(Agent.Response r) {
        List<String> out = new ArrayList<>();
        Object q = r.structuredOutput().get("questions");
        if (q instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object t = m.get("question");
                    if (t == null) {
                        t = m.values().stream().findFirst().orElse(null);
                    }
                    if (t != null && !t.toString().isBlank()) {
                        out.add(t.toString());
                    }
                } else if (item != null && !item.toString().isBlank()) {
                    out.add(item.toString());
                }
            }
        } else if (q != null && !q.toString().isBlank()) {
            out.add(q.toString());
        }
        if (out.isEmpty()) {
            r.escalationReason().ifPresent(out::add);
        }
        return out;
    }

    private Optional<String> safeAsk(String projectId, List<String> questions, Task task) {
        try {
            return clarifier.ask(projectId, questions, task.title() + ": " + task.description());
        } catch (RuntimeException e) {
            return Optional.empty(); // never let a clarification round crash the task
        }
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
            AgentRole devRole = developerToFix(projectId, task);
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
    private AgentRole developerToFix(String projectId, Task task) {
        Map<String, Handoff> board = handoffs.getOrDefault(projectId, Map.of());
        for (TaskId dep : task.dependsOn()) {
            Handoff h = board.get(dep.value());
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
    private Map<String, String> upstreamHandoffs(String projectId, Task task) {
        Map<String, String> upstream = new LinkedHashMap<>();
        Map<String, Handoff> board = handoffs.getOrDefault(projectId, Map.of());
        for (TaskId dep : task.dependsOn()) {
            Handoff h = board.get(dep.value());
            if (h != null && !h.summary().isBlank()) {
                // e.g. "from_BACKEND_ARCHITECT" -> the architect's spec/instructions.
                upstream.put("from_" + h.role(), h.summary());
            }
        }
        return upstream;
    }

    /** Record a task's output so dependents can build on it; audit the hand-off for the live view. */
    private void recordHandoff(String projectId, Task task, Agent agent, Agent.Response response) {
        if (response == null) {
            return;
        }
        // Pass the FULL upstream output downstream (capped only to avoid pathological sizes), not a
        // 280-char stub — later agents need the real architecture/schema/instructions, not a teaser.
        String summary = response.escalationReason().isPresent()
                ? "" : handoffText(String.valueOf(response.structuredOutput()));
        // Prefer concrete instructions/spec/tokens fields if the agent produced them.
        for (String key : List.of("instructions", "specification", "tokens", "schema", "summary")) {
            Object v = response.structuredOutput().get(key);
            if (v != null && !v.toString().isBlank()) {
                summary = handoffText(v.toString());
                break;
            }
        }
        handoffs.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>())
                .put(task.id().value(), new Handoff(agent.role().name(), summary));
    }

    /** Release a finished project's collaboration board so it isn't retained for the process's life. */
    @Override
    public void onProjectComplete(String projectId) {
        handoffs.remove(projectId);
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

    /** Cap on hand-off / grounding text passed between agents — generous (keeps full specs/schemas)
     *  but bounded so a runaway output can't bloat every downstream prompt. */
    private static final int HANDOFF_MAX_CHARS = 12_000;

    /** Roles that review actual code and so need the real file contents, not just summaries. */
    private static boolean needsFileAccess(AgentRole role) {
        return role == AgentRole.CODE_REVIEWER || role == AgentRole.SECURITY_REVIEWER
                || role == AgentRole.QA_ENGINEER;
    }

    /** Add the actual committed project files to a reviewer's/QA's grounding (capped). */
    private void injectProjectFiles(Map<String, String> grounding) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        try {
            for (String path : artifactRepository.list("")) {
                if (path.startsWith(".git/") || path.equals(".project/knowledge.md")) {
                    continue;
                }
                Optional<String> content = artifactRepository.read(path);
                if (content.isEmpty() || content.get().isBlank()) {
                    continue;
                }
                String body = content.get();
                if (body.length() > 8_000) {
                    body = body.substring(0, 8_000) + "\n…[truncated]";
                }
                String entry = "--- " + path + " ---\n" + body + "\n\n";
                if (total + entry.length() > 48_000) {
                    break; // keep the grounding bounded; reviewers see the most files that fit
                }
                sb.append(entry);
                total += entry.length();
            }
        } catch (RuntimeException e) {
            return; // never let file-reading break the task
        }
        if (sb.length() > 0) {
            grounding.put("projectFiles", sb.toString().strip());
        }
    }

    /** Full text for downstream grounding, stripped and capped only against pathological sizes. */
    private static String handoffText(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= HANDOFF_MAX_CHARS ? t
                : t.substring(0, HANDOFF_MAX_CHARS) + "\n…[truncated]";
    }

    /**
     * Run the Prompt Engineer to sharpen the prompt — but only when it pays. It is skipped for
     * meta-roles, when PROMPT_ENGINEER isn't configured, and (the new gate) when the task is already
     * well-specified, since the PE then just paraphrases and doubles the step count. When it does
     * run, it gets the upstream artifacts as grounding so it sharpens with real context.
     * Best-effort — never fails the task.
     */
    private String refinePrompt(String projectId, Task task, Map<String, String> upstream) {
        if (!needsRefinement(task.assignedRole()) || !agentFactory.supports(AgentRole.PROMPT_ENGINEER)
                || isWellSpecified(task)) {
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
            // Give the PE the same upstream context the worker will get, so it references real
            // artifacts (schema, API contract) instead of reconstructing them from memory.
            Agent.Request request = new Agent.Request(task, ask, upstream, Map.of());
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
            // don't refine the meta-roles (planning/research/QA/knowledge/explanation/retrospective)
            case PROMPT_ENGINEER, BUSINESS_ANALYST, MARKET_RESEARCHER, TEAM_LEAD, QA_ENGINEER,
                 KNOWLEDGE_CURATOR, PROJECT_EXPLAINER, RETROSPECTIVE_ANALYST -> false;
            default -> true;
        };
    }

    /**
     * Whether a task is already detailed enough that the Prompt Engineer would only paraphrase it.
     * Heuristic (from real run feedback): a long description, or one that already spells out
     * acceptance criteria / numbered deliverables / explicit file paths, is left as-is.
     */
    private static boolean isWellSpecified(Task task) {
        String d = task.description();
        if (d == null) {
            return false;
        }
        String lower = d.toLowerCase();
        int words = d.trim().isEmpty() ? 0 : d.trim().split("\\s+").length;
        boolean hasCriteria = lower.contains("acceptance criteria") || lower.contains("deliverable")
                || lower.matches("(?s).*(^|\\n)\\s*\\d+[.)].*");        // a numbered list
        return words >= 120 || (words >= 60 && hasCriteria);
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

