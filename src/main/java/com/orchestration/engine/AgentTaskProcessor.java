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
import com.orchestration.verify.ProjectBuildVerifier;
import com.orchestration.workspace.ProjectWorkspaces;

import java.time.Duration;

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
    // When the agent role-player can read the filesystem itself (MCP mode: Claude Code drives), we
    // hand reviewers/QA the repo path + file LIST instead of inlining tens of KB of file contents —
    // they open the real files on demand. This keeps the grounding (and the driver's context) small.
    private final boolean agentsReadFilesDirectly;
    // Optional: when present, each project's repo/dir/knowledge is resolved per project id (isolated
    // workspaces). When null, the single shared repository/workingDir/knowledgeStore above are used.
    private final ProjectWorkspaces workspaces;
    // Optional: when present (mcp profile), QA verification runs the REAL build command against the
    // project folder and gates on the actual exit code, instead of trusting the role-played QA agent.
    private final ProjectBuildVerifier buildVerifier;

    /** How long a real build/test run may take before it is killed and treated as a failure. */
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);

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
        this(agentFactory, artifactRepository, auditLog, workingDir, defaultTestCommand,
                maxReworkAttempts, knowledgeStore, clarifier, false);
    }

    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog,
                              String workingDir,
                              List<String> defaultTestCommand,
                              int maxReworkAttempts,
                              ProjectKnowledgeStore knowledgeStore,
                              ClarificationGateway clarifier,
                              boolean agentsReadFilesDirectly) {
        this(agentFactory, artifactRepository, auditLog, workingDir, defaultTestCommand,
                maxReworkAttempts, knowledgeStore, clarifier, agentsReadFilesDirectly, null, null);
    }

    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog,
                              String workingDir,
                              List<String> defaultTestCommand,
                              int maxReworkAttempts,
                              ProjectKnowledgeStore knowledgeStore,
                              ClarificationGateway clarifier,
                              boolean agentsReadFilesDirectly,
                              ProjectWorkspaces workspaces) {
        this(agentFactory, artifactRepository, auditLog, workingDir, defaultTestCommand,
                maxReworkAttempts, knowledgeStore, clarifier, agentsReadFilesDirectly, workspaces, null);
    }

    public AgentTaskProcessor(AgentFactory agentFactory,
                              ArtifactRepository artifactRepository,
                              AuditLog auditLog,
                              String workingDir,
                              List<String> defaultTestCommand,
                              int maxReworkAttempts,
                              ProjectKnowledgeStore knowledgeStore,
                              ClarificationGateway clarifier,
                              boolean agentsReadFilesDirectly,
                              ProjectWorkspaces workspaces,
                              ProjectBuildVerifier buildVerifier) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.workingDir = Objects.requireNonNull(workingDir, "workingDir");
        this.defaultTestCommand = List.copyOf(defaultTestCommand);
        this.maxReworkAttempts = Math.max(0, maxReworkAttempts);
        this.knowledgeStore = knowledgeStore;
        this.clarifier = clarifier;
        this.agentsReadFilesDirectly = agentsReadFilesDirectly;
        this.workspaces = workspaces;
        this.buildVerifier = buildVerifier;
    }

    // Per-project resolution: with a workspace provider, each project has its OWN repo/dir/knowledge;
    // without one, every project shares the single repository/workingDir/knowledgeStore (legacy/tests).
    private ArtifactRepository repo(String projectId) {
        return workspaces == null ? artifactRepository : workspaces.get(projectId).repository();
    }

    private String dir(String projectId) {
        return workspaces == null ? workingDir : workspaces.get(projectId).dir();
    }

    private ProjectKnowledgeStore knowledge(String projectId) {
        return workspaces == null ? knowledgeStore : workspaces.get(projectId).knowledge();
    }

    @Override
    public Agent.Response process(String projectId, Task task) {
        Agent agent = agentFactory.create(task.assignedRole());

        Map<String, Object> baseParams = new LinkedHashMap<>();
        baseParams.put("workingDir", dir(projectId));
        baseParams.put("testCommand", detectedOrDefaultCommand(projectId));
        baseParams.putAll(task.metadata());

        // Collaboration: gather the outputs of this task's completed dependencies as grounding.
        Map<String, String> upstream = upstreamHandoffs(projectId, task);
        // Reviewers and QA review REAL code, not summaries: hand them the actual committed file
        // contents so findings are grounded in what was produced, not in developers' descriptions.
        if (needsFileAccess(task.assignedRole())) {
            injectProjectFiles(projectId, upstream);
        } else if (agentsReadFilesDirectly && writesCode(task.assignedRole())) {
            // Anti-drift: a developer's prose hand-off conveys the architect's/DBA's *intent*, but the
            // *contract* lives in the files they produced (schema, shared types). In MCP mode the
            // developer (Claude Code) can open those files, so point it at them instead of letting it
            // re-derive types from a summary and silently diverge.
            injectProjectFileList(projectId, upstream);
            // Shift-left safety net: have the developer build/test before submitting, rather than
            // letting failures surface only at the QA step and bounce back as rework.
            upstream.put("definitionOfDone", buildBeforeSubmit(projectId, task));
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
            // Non-blocking path: a clean review still may carry HIGH/MEDIUM findings that the
            // reviewer chose not to block on. Those used to be advisory-only — dropped unless the
            // next agent happened to notice them in grounding (a real hardening was nearly lost
            // that way). Now they are dispatched to a developer as a remediation pass.
            remediateAdvisoryFindings(projectId, task, reviewer, baseParams, upstream, review);
            return review;
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

    /**
     * Dispatch a developer to address a reviewer's non-blocking but severe (CRITICAL/HIGH/MEDIUM)
     * findings, so they enter the work as a real task instead of riding along as advisory grounding.
     * Skipped when the reviewer already supplied corrected artifacts, when no developer role is
     * configured, or when the findings carry no severe items. Best-effort — never fails the review.
     */
    private void remediateAdvisoryFindings(String projectId, Task task, Agent reviewer,
                                           Map<String, Object> baseParams, Map<String, String> upstream,
                                           Agent.Response review) {
        if (!review.artifacts().isEmpty()) {
            return; // the reviewer already committed its own fixes
        }
        String severe = severeFindings(review);
        if (severe.isBlank()) {
            return;
        }
        AgentRole devRole = developerToFix(projectId, task);
        if (!agentFactory.supports(devRole)) {
            return;
        }
        try {
            Agent developer = agentFactory.create(devRole);
            Task fixTask = new Task(TaskId.random(), "Address review findings", task.description(),
                    devRole, WorkflowState.IN_PROGRESS, List.of(), Map.of(), Instant.now(), Instant.now());
            String fixInstructions = "A reviewer (" + reviewer.role() + ") approved the work but raised "
                    + "findings that still must be addressed (CRITICAL/HIGH/MEDIUM). Apply the "
                    + "remediations for real and return the corrected files as artifacts.\n\nFindings:\n"
                    + severe;
            Map<String, String> fixGrounding = new LinkedHashMap<>(upstream);
            fixGrounding.put("reviewFindings", severe);
            dispatch(projectId, fixTask, developer, fixInstructions, fixGrounding, baseParams,
                    reviewer.role().name(),
                    "Remediating non-blocking " + reviewer.role() + " findings by " + devRole, 1);
        } catch (RuntimeException e) {
            audit(projectId, task, reviewer, AuditLog.EventType.ERROR,
                    "Advisory-finding remediation could not be dispatched: " + e, Map.of());
        }
    }

    /** The CRITICAL/HIGH/MEDIUM items from a reviewer's output.findings, as developer-actionable
     *  text; "" when there are none (or severities can't be recognized). */
    private static String severeFindings(Agent.Response review) {
        Object findings = review.structuredOutput().get("findings");
        if (findings instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object severity = m.get("severity");
                    if (severity != null && isSevere(severity.toString())) {
                        sb.append("- ").append(m).append('\n');
                    }
                }
            }
            return sb.toString().strip();
        }
        if (findings != null) {
            String text = findings.toString();
            // Free-text findings: only act when an explicit severity marker is present.
            if (text.matches("(?s).*\\b(CRITICAL|HIGH|MEDIUM)\\b.*")) {
                return text.strip();
            }
        }
        return "";
    }

    private static boolean isSevere(String severity) {
        String s = severity.strip().toUpperCase(java.util.Locale.ROOT);
        return s.startsWith("CRITICAL") || s.startsWith("HIGH") || s.startsWith("MEDIUM");
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
            commitArtifacts(projectId, task, agent, response, attempt);

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
        // In mcp mode, prefer running the REAL build command (a verified exit code) over the role-played
        // QA agent's claim. If the toolchain can't even start, fall back to the role-played path so an
        // environment issue doesn't block an otherwise-good project.
        boolean realBuild = buildVerifier != null && agentsReadFilesDirectly;
        Agent.Response response = realBuild ? runRealBuild(projectId, task, qa, baseParams, 0) : null;
        if (response == null) {
            realBuild = false;
            response = dispatch(projectId, task, qa, task.description(), upstream,
                    baseParams, "developers (verifying their build)", "Verifying build for " + qa.role(), 0);
        }

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

            Agent.Response reverify = realBuild
                    ? runRealBuild(projectId, task, qa, baseParams, attempt) : null;
            response = reverify != null ? reverify
                    : dispatch(projectId, task, qa, task.description(), upstream, baseParams,
                            devRole.name(), "Re-verifying build (attempt " + attempt + ")", attempt);
        }
        return response;
    }

    /**
     * Actually run the project's test command and turn the real exit code into a QA outcome (green →
     * COMPLETED, non-zero/timeout → NEEDS_REVIEW with the output so a developer can fix it). Returns
     * null when the command couldn't be launched at all — the caller then degrades to role-played QA.
     */
    private Agent.Response runRealBuild(String projectId, Task task, Agent qa,
                                        Map<String, Object> baseParams, int attempt) {
        List<String> command = resolveTestCommand(baseParams);
        String dir = dir(projectId);
        audit(projectId, task, qa, AuditLog.EventType.PROMPT,
                "Verifying the real build: " + String.join(" ", command) + " in " + dir,
                Map.of("role", qa.role().name(), "collaborator", "build",
                        "prompt", String.join(" ", command) + "\n(cwd: " + dir + ")"));
        ProjectBuildVerifier.Result r = buildVerifier.verify(dir, command, BUILD_TIMEOUT);
        if (r.couldNotStart()) {
            audit(projectId, task, qa, AuditLog.EventType.STATE_CHANGE,
                    "Real build could not run; falling back to review",
                    Map.of("role", qa.role().name(), "detail", r.output()));
            return null;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("command", command);
        output.put("exitCode", r.exitCode());
        output.put("timedOut", r.timedOut());
        output.put("stdout", r.output());
        boolean green = r.success();
        audit(projectId, task, qa, AuditLog.EventType.RESPONSE,
                "Build " + (green ? "PASSED" : "FAILED (exit " + r.exitCode() + ")")
                        + (attempt > 0 ? " (re-verify #" + attempt + ")" : ""),
                Map.of("role", qa.role().name(),
                        "outcome", (green ? Agent.Outcome.COMPLETED : Agent.Outcome.NEEDS_REVIEW).name(),
                        "detail", green ? "build green" : summarizeText(r.output())));
        Optional<String> reason = green ? Optional.empty()
                : Optional.of("Build failed (exit " + r.exitCode() + (r.timedOut() ? ", timed out" : "") + ")");
        return new Agent.Response(green ? Agent.Outcome.COMPLETED : Agent.Outcome.NEEDS_REVIEW,
                output, List.of(), Agent.Confidence.HIGH, List.of(), reason);
    }

    /** The effective test command for this task: a per-task override (task metadata) else the default. */
    private List<String> resolveTestCommand(Map<String, Object> baseParams) {
        Object cmd = baseParams.get("testCommand");
        if (cmd instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        return defaultTestCommand;
    }

    /**
     * The test command derived from what is ACTUALLY in the workspace (gradle/maven/pytest/npm/…),
     * falling back to the configured default only when nothing is recognized — e.g. a fresh, still
     * empty workspace. Re-detected on every use because the workspace gains its marker files as the
     * build progresses; a per-task {@code testCommand} in task metadata still wins over both.
     */
    private List<String> detectedOrDefaultCommand(String projectId) {
        try {
            return com.orchestration.verify.StackDetector
                    .testCommand(java.nio.file.Path.of(dir(projectId)))
                    .orElse(defaultTestCommand);
        } catch (RuntimeException e) {
            return defaultTestCommand; // never let detection break a task
        }
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
        commitArtifacts(projectId, task, agent, response, attempt);
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
        // The full upstream output is the hand-off (capped only to avoid pathological sizes), not a
        // 280-char stub — later agents need the real architecture/schema/instructions, not a teaser.
        String raw = response.escalationReason().isPresent()
                ? "" : String.valueOf(response.structuredOutput());
        // Prefer concrete instructions/spec/tokens fields if the agent produced them.
        for (String key : List.of("instructions", "specification", "tokens", "schema", "summary")) {
            Object v = response.structuredOutput().get(key);
            if (v != null && !v.toString().isBlank()) {
                raw = v.toString();
                break;
            }
        }
        // The hand-off is prose, NOT a committed file, so anything past the cap is unrecoverable by a
        // downstream agent. Don't drop it silently: surface a visible warning so it can be acted on
        // (e.g. by having this role commit its spec as a file).
        warnIfHandoffTruncated(projectId, task, agent, raw);
        handoffs.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>())
                .put(task.id().value(), new Handoff(agent.role().name(), handoffText(raw)));
    }

    /** Emit a loud, auditable warning when a hand-off is clipped by the cap — so truncation is never
     *  silent. Rare in practice; if you see it fire, that role's spec should become a file. */
    private void warnIfHandoffTruncated(String projectId, Task task, Agent agent, String raw) {
        if (raw == null || raw.length() <= HANDOFF_MAX_CHARS) {
            return;
        }
        int dropped = raw.length() - HANDOFF_MAX_CHARS;
        String message = "⚠ " + agent.role() + " hand-off truncated: " + dropped + " of " + raw.length()
                + " chars dropped (cap " + HANDOFF_MAX_CHARS + "). Downstream agents may miss detail — "
                + "have this role commit its spec as a file so nothing is lost.";
        audit(projectId, task, agent, AuditLog.EventType.ERROR, message,
                Map.of("role", agent.role().name(), "detail", message));
        System.err.println("[handoff] " + message + " (project " + projectId
                + ", task " + task.id().value() + ")");
    }

    /** Release a finished project's collaboration board so it isn't retained for the process's life. */
    @Override
    public void onProjectComplete(String projectId) {
        handoffs.remove(projectId);
    }

    /** When the Knowledge Curator finishes, commit its brief as the project knowledge file. */
    private void persistKnowledge(String projectId, Task task, Agent agent, Agent.Response response) {
        ProjectKnowledgeStore store = knowledge(projectId);
        if (store == null || agent.role() != AgentRole.KNOWLEDGE_CURATOR || response == null) {
            return;
        }
        Object knowledge = response.structuredOutput().getOrDefault("knowledge",
                response.structuredOutput().get("summary"));
        if (knowledge == null || knowledge.toString().isBlank()) {
            return;
        }
        try {
            store.save(knowledge.toString(), task.id().value(), agent.role().name());
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

    /** Cap on file paths enumerated in the projectFiles grounding; the agent lists the rest itself. */
    private static final int FILE_LIST_MAX_ENTRIES = 150;

    /** Dependency / build / cache directories that must never appear in the projectFiles grounding.
     *  The repo's list() walks the whole working tree, so without this a single npm/pip install dumps
     *  thousands of vendored files into every task — wasting tokens and pushing the real source past the
     *  truncation cap. Matched as a path segment (e.g. ".venv/" or "node_modules/") so nesting is caught. */
    private static final java.util.Set<String> GROUNDING_NOISE_DIRS = java.util.Set.of(
            "node_modules", ".venv", "venv", "env", "__pycache__", ".pytest_cache", ".mypy_cache",
            ".ruff_cache", ".tox", ".eggs", "build", "dist", "out", "target", ".gradle", ".idea",
            ".vscode", ".vite", ".next", ".nuxt", ".svelte-kit", ".cache", "coverage", ".nyc_output",
            "vendor", ".terraform", "bin", "obj", ".angular", ".parcel-cache");

    /** Noise files that add bytes but no signal for a reviewer. */
    private static final java.util.Set<String> GROUNDING_NOISE_FILES = java.util.Set.of(
            ".ds_store", "thumbs.db", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            "poetry.lock", "cargo.lock", "composer.lock");

    /** True when a repo path is dependency/build/cache noise that should be kept out of grounding. */
    private static boolean isGroundingNoise(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        for (String segment : path.split("/")) {
            if (GROUNDING_NOISE_DIRS.contains(segment)
                    || segment.endsWith(".egg-info") || segment.endsWith(".dist-info")) {
                return true;
            }
        }
        int slash = path.lastIndexOf('/');
        String name = (slash >= 0 ? path.substring(slash + 1) : path).toLowerCase(java.util.Locale.ROOT);
        return GROUNDING_NOISE_FILES.contains(name);
    }

    /** Roles that review actual code and so need the real file contents, not just summaries. */
    private static boolean needsFileAccess(AgentRole role) {
        return role == AgentRole.CODE_REVIEWER || role == AgentRole.SECURITY_REVIEWER
                || role == AgentRole.QA_ENGINEER;
    }

    /** The code-writing developer roles — they produce real source and can build/test their work. */
    private static boolean writesCode(AgentRole role) {
        return role == AgentRole.BACKEND_DEVELOPER || role == AgentRole.FRONTEND_DEVELOPER
                || role == AgentRole.AI_ML_DEVELOPER;
    }

    /** The "build it green before you submit" definition-of-done handed to a developer in MCP mode,
     *  spelling out the repo location and the exact test command so the brain can actually run it.
     *  The command is detected from the project's real stack (gradle/pytest/npm/…) — never a
     *  hardcoded toolchain — with a per-task metadata override winning over detection. */
    private String buildBeforeSubmit(String projectId, Task task) {
        Object cmd = task.metadata().getOrDefault("testCommand", detectedOrDefaultCommand(projectId));
        String command = cmd instanceof List<?> parts
                ? String.join(" ", parts.stream().map(String::valueOf).toList())
                : String.valueOf(cmd);
        String repoPath = java.nio.file.Path.of(dir(projectId)).toAbsolutePath().normalize().toString();
        return "Before you submit: build and test your work in " + repoPath + " by running `" + command
                + "` and make sure it compiles and every test passes. If that command does not match "
                + "the stack you are actually building (e.g. the project is still empty), run the "
                + "stack's standard test command instead and name it in your summary. If it fails, fix "
                + "it first — do NOT submit a red build; that just bounces back as rework from QA.";
    }

    /** Give a reviewer/QA the project files. When the agent can read the filesystem itself (MCP mode),
     *  hand it the repo location + file LIST so it opens the real files on demand; otherwise inline the
     *  contents (an LLM agent has no filesystem). The list form keeps the grounding tiny — inlining can
     *  reach ~48KB, which previously blew past the MCP tool-result limit and bloated the driver's context. */
    private void injectProjectFiles(String projectId, Map<String, String> grounding) {
        if (agentsReadFilesDirectly) {
            injectProjectFileList(projectId, grounding);
        } else {
            injectProjectFileContents(projectId, grounding);
        }
    }

    /** MCP mode: list the repo's files and point the agent at the real directory to read them itself. */
    private void injectProjectFileList(String projectId, Map<String, String> grounding) {
        List<String> files = new ArrayList<>();
        try {
            for (String path : repo(projectId).list("")) {
                if (path.startsWith(".git/") || path.equals(".project/knowledge.md")
                        || isGroundingNoise(path)) {
                    continue;
                }
                files.add(path);
            }
        } catch (RuntimeException e) {
            return; // never let file-listing break the task
        }
        if (files.isEmpty()) {
            return;
        }
        String repoPath = java.nio.file.Path.of(dir(projectId)).toAbsolutePath().normalize().toString();
        StringBuilder sb = new StringBuilder(
                "Review the REAL, current project files. They live in this repository on disk:\n")
                .append(repoPath).append('\n')
                .append("Open and read them directly with your tools (do NOT rely on summaries). Files:\n");
        // Bounded: a large project's full tree repeated into every task is pure token waste — the
        // agent can list the directory itself for anything beyond the cap.
        int shown = Math.min(files.size(), FILE_LIST_MAX_ENTRIES);
        for (String f : files.subList(0, shown)) {
            sb.append("  - ").append(f).append('\n');
        }
        if (files.size() > shown) {
            sb.append("  …and ").append(files.size() - shown)
                    .append(" more — list the directory with your tools to see the rest.\n");
        }
        grounding.put("projectFiles", sb.toString().strip());
    }

    /** LLM mode: inline the actual committed file contents into grounding (capped). */
    private void injectProjectFileContents(String projectId, Map<String, String> grounding) {
        ArtifactRepository repository = repo(projectId);
        StringBuilder sb = new StringBuilder();
        int total = 0;
        try {
            for (String path : repository.list("")) {
                if (path.startsWith(".git/") || path.equals(".project/knowledge.md")
                        || isGroundingNoise(path)) {
                    continue;
                }
                Optional<String> content = repository.read(path);
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
     * Run the Prompt Engineer to sharpen the prompt — but only when it pays. It is skipped for every
     * role except the code-writing developers (see {@link #needsRefinement}), when PROMPT_ENGINEER
     * isn't configured, and when the task is already well-specified, since the PE then just paraphrases
     * and doubles the step count. When it does run, it gets the upstream artifacts as grounding so it
     * sharpens with real context. Best-effort — never fails the task.
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
                    // Show the refined prompt at full length (capped only against pathological sizes),
                    // not the 280-char summary — otherwise the UI looks like the PE is clipping it.
                    Map.of("role", "PROMPT_ENGINEER", "detail",
                            refinedText.isBlank() ? "(no refinement)" : handoffText(refinedText)),
                    Instant.now()));
            return refinedText;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private boolean needsRefinement(AgentRole role) {
        // Only the code-writing roles benefit from refinement: the PE adds checkable acceptance
        // criteria and the "produce REAL code, never a stub/placeholder/screenshot" guardrail, which
        // measurably changes what a developer emits. Architects, the DBA, designers and writers get a
        // planner spec that is already detailed enough that the PE would only paraphrase it — so we
        // skip them and save the extra LLM round-trip. (The PE can never remove detail: the worker
        // always receives the full task description; the refined prompt is purely additive grounding.)
        return switch (role) {
            case BACKEND_DEVELOPER, FRONTEND_DEVELOPER, AI_ML_DEVELOPER -> true;
            default -> false;
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
        return words >= 80 || (words >= 50 && hasCriteria);
    }

    /** Artifact content meaning "I already wrote this file to disk with my own tools — commit what
     *  is there." This is the blessed alternative to re-pasting full file bodies (token-heavy) or
     *  omitting the file entirely (the orchestrator's record then misses the change). */
    public static final String ON_DISK_MARKER = "<<on-disk>>";

    private void commitArtifacts(String projectId, Task task, Agent agent, Agent.Response response,
                                 int attempt) {
        if (response.artifacts().isEmpty()) {
            return;
        }
        ArtifactRepository repository = repo(projectId);
        List<ArtifactRepository.FileChange> changes = new ArrayList<>();
        for (var a : response.artifacts()) {
            String content = a.content();
            if (content != null && content.strip().equalsIgnoreCase(ON_DISK_MARKER)) {
                Optional<String> onDisk = readQuietly(repository, a.path());
                if (onDisk.isEmpty()) {
                    String message = "⚠ Artifact " + a.path() + " was marked " + ON_DISK_MARKER
                            + " but no such file exists in the workspace — dropped. Write the file "
                            + "first, or supply its full content.";
                    audit(projectId, task, agent, AuditLog.EventType.ERROR, message,
                            Map.of("role", agent.role().name(), "detail", message, "path", a.path()));
                    continue;
                }
                changes.add(new ArtifactRepository.FileChange(a.path(), onDisk.get()));
                continue;
            }
            if (wouldClobberRealFile(repository, projectId, task, agent, a.path(), content)) {
                continue; // dropped + warned below — never let a summary stub overwrite real source
            }
            changes.add(new ArtifactRepository.FileChange(a.path(), content));
        }
        if (changes.isEmpty()) {
            return;
        }
        String suffix = attempt > 0 ? " (rework #" + attempt + ")" : "";
        repository.write(new ArtifactRepository.WriteRequest(
                task.id().value(), agent.role().name(),
                "[" + agent.role() + "] " + task.title() + suffix, changes));
    }

    private static Optional<String> readQuietly(ArtifactRepository repository, String path) {
        try {
            return repository.read(path);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Guard against the most damaging submit failure: an agent lists a file in {@code artifacts} with a
     *  short placeholder ("see repo", "// unchanged", a one-line summary) instead of the full content,
     *  and the verbatim write CLOBBERS real, working source with a stub. We refuse a write that would
     *  shrink an existing non-trivial file by &gt;90%, or replace it with obvious placeholder text, and
     *  emit a loud audit warning so the drop is never silent. A genuinely empty/new path is unaffected. */
    private boolean wouldClobberRealFile(ArtifactRepository repository, String projectId, Task task,
                                         Agent agent, String path, String incoming) {
        String existing;
        try {
            existing = repository.read(path).orElse(null);
        } catch (RuntimeException e) {
            return false; // can't read the prior version → don't block the write
        }
        if (existing == null || existing.strip().length() < 200) {
            return false; // new file, or prior content too small to be worth protecting
        }
        String body = incoming == null ? "" : incoming.strip();
        boolean massiveShrink = body.length() < existing.strip().length() * 0.10;
        boolean placeholder = body.length() < 200 && looksLikePlaceholder(body);
        if (!massiveShrink && !placeholder) {
            return false;
        }
        String why = placeholder ? "placeholder/summary text" : "a >90% shrink (" + body.length()
                + " vs " + existing.strip().length() + " chars)";
        String message = "⚠ Refused to overwrite " + path + " with " + why + " from " + agent.role()
                + ". A truncated artifact would have clobbered real source — the existing file is kept. "
                + "Re-submit with the FULL file content, or omit it from artifacts if you wrote it directly.";
        audit(projectId, task, agent, AuditLog.EventType.ERROR, message,
                Map.of("role", agent.role().name(), "detail", message, "path", path));
        System.err.println("[artifacts] " + message + " (project " + projectId
                + ", task " + task.id().value() + ")");
        return true;
    }

    /** Heuristic: short artifact content that reads like a reference/summary rather than real code. */
    private static boolean looksLikePlaceholder(String body) {
        if (body.isBlank()) {
            return true;
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("see repo") || lower.contains("see above") || lower.contains("unchanged")
                || lower.contains("no change") || lower.contains("omitted") || lower.contains("as before")
                || lower.contains("[truncated") || lower.contains("…") || lower.startsWith("// see")
                || lower.startsWith("# see") || lower.equals("...") || lower.startsWith("placeholder");
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

