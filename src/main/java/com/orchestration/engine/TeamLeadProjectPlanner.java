package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentRole;
import com.orchestration.agent.SkillRegistry;
import com.orchestration.audit.AuditLog;
import com.orchestration.knowledge.ProjectKnowledgeStore;
import com.orchestration.phase.PhasePlan;
import com.orchestration.phase.PhasePlanStore;
import com.orchestration.workspace.ProjectWorkspaces;
import com.orchestration.task.InMemoryTaskGraph;
import com.orchestration.task.Task;
import com.orchestration.task.TaskGraph;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The real {@link ProjectPlanner}: asks the Team Lead agent to decompose the feature request and
 * turns its {@code output.tasks} into a {@link TaskGraph}.
 *
 * <p>If the Team Lead cannot decompose (too ambiguous, or returns no tasks), the planner produces a
 * single Team Lead task. Running it lets the agent escalate through the normal human-in-the-loop
 * gate rather than the planner silently inventing work.
 */
public class TeamLeadProjectPlanner implements ProjectPlanner {

    /** Hard cap on clarification rounds so a never-satisfied loop can't run forever. */
    private static final int MAX_CLARIFICATION_ROUNDS = 5;

    private final AgentFactory agentFactory;
    private final AuditLog auditLog; // optional; when present, planning steps stream to the UI
    private final ClarificationGateway clarifier; // optional; when present, the BA loop asks the user
    private final ProjectKnowledgeStore knowledgeStore; // optional; the prior project brief, if any
    // Optional: when present, every project gets its OWN workspace (dir + repo + knowledge), opened
    // here at planning time before any task runs. When null, the legacy single shared repo is used.
    private final ProjectWorkspaces workspaces;
    // Optional: lets the Skill Smith persist an approved, researched domain skill mid-run so later
    // agents in THIS run (and future runs) pick it up. Null disables just-in-time skill synthesis.
    private final SkillRegistry skills;

    public TeamLeadProjectPlanner(AgentFactory agentFactory) {
        this(agentFactory, null, null, null);
    }

    public TeamLeadProjectPlanner(AgentFactory agentFactory, AuditLog auditLog) {
        this(agentFactory, auditLog, null, null);
    }

    public TeamLeadProjectPlanner(AgentFactory agentFactory, AuditLog auditLog,
                                  ClarificationGateway clarifier) {
        this(agentFactory, auditLog, clarifier, null);
    }

    public TeamLeadProjectPlanner(AgentFactory agentFactory, AuditLog auditLog,
                                  ClarificationGateway clarifier, ProjectKnowledgeStore knowledgeStore) {
        this(agentFactory, auditLog, clarifier, knowledgeStore, null);
    }

    public TeamLeadProjectPlanner(AgentFactory agentFactory, AuditLog auditLog,
                                  ClarificationGateway clarifier, ProjectKnowledgeStore knowledgeStore,
                                  ProjectWorkspaces workspaces) {
        this(agentFactory, auditLog, clarifier, knowledgeStore, workspaces, null);
    }

    public TeamLeadProjectPlanner(AgentFactory agentFactory, AuditLog auditLog,
                                  ClarificationGateway clarifier, ProjectKnowledgeStore knowledgeStore,
                                  ProjectWorkspaces workspaces, SkillRegistry skills) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.auditLog = auditLog;
        this.clarifier = clarifier;
        this.knowledgeStore = knowledgeStore;
        this.workspaces = workspaces;
        this.skills = skills;
    }

    /** The result of the clarification loop: the agreed spec plus the accumulated Q&amp;A trail. */
    private record Clarified(String specification, String clarifications) {}

    private void planAudit(String projectId, String actor, AuditLog.EventType type,
                           String summary, Map<String, Object> details) {
        if (auditLog == null) {
            return;
        }
        auditLog.record(new AuditLog.AuditEvent(UUID.randomUUID().toString(), projectId, null,
                actor, type, summary, details, Instant.now()));
    }

    @Override
    public TaskGraph plan(String projectId, OrchestrationEngine.ProjectRequest request) {
        // An edit run targets an EXISTING project folder (orchestrate_edit). It always reads the prior
        // context so the team modifies the current code rather than rebuilding it from scratch.
        boolean editMode = isEdit(request);
        // A phase-continuation run (orchestrate_phases build=true) is scoped to an already-agreed
        // roadmap phase: it must NOT re-run market research, the BA clarification loop, or skill
        // synthesis — re-asking the user stalls the run on a stale BA prompt and the scope is already
        // settled. The prior project brain + the phase scope carry all the context it needs.
        boolean phaseContinue = isPhaseContinue(request);
        // Opt-in: the project brain is only worth its cost for projects you'll continue across
        // sessions. It is OFF by default for fresh builds, but always on for edits.
        boolean rememberProject = rememberProject(request) || editMode;

        // Open this project's workspace up front so every downstream task writes there, not into a repo
        // shared with other runs. A fresh build creates ~/<base>/<slug>; an edit reuses the existing
        // folder. Resolve the matching knowledge store; falls back to the legacy single store when no
        // provider is wired.
        ProjectKnowledgeStore knowledge = resolveWorkspaceKnowledge(projectId, request, editMode);

        // 0) Prior project brain: if remembering is on and this repo already has a committed knowledge
        //    file, hand it to the team as context so an EDIT starts from understanding, not a re-read.
        String priorKnowledge = rememberProject ? loadPriorKnowledge(knowledge) : "";

        // 1) Market Researcher studies comparable tools, their complaints, and recommends features —
        //    up front, so the questions the BA asks the user are informed by what the market shows.
        //    Skipped on a phase continuation: the roadmap is already set.
        String marketResearch = phaseContinue ? "" : researchMarket(projectId, request, priorKnowledge);

        // 2) Clarification loop: the BA drafts a spec, asks the user the open questions, folds the
        //    answers back in, and repeats until the understanding is confirmed (or the cap is hit).
        //    Skipped on a phase continuation — re-asking the user is what stalled the run on the BA
        //    prompt between phases; the phase scope + prior knowledge are enough to plan the slice.
        Clarified clarified = phaseContinue
                ? new Clarified("", "")
                : clarifyRequirements(projectId, request, marketResearch, priorKnowledge);
        String specification = clarified.specification();

        // 2.4) Just-in-time skills: if this build needs specialised domain expertise the team has no
        //      skill for, the Skill Smith researches it and proposes a tight skill; the user approves
        //      it here, BEFORE any build agent is created, so the approved skill is on disk and every
        //      downstream agent this run (and future runs) picks it up. Skipped on a phase
        //      continuation (any needed skill was decided when the phased build started).
        if (!phaseContinue) {
            synthesizeSkills(projectId, request, specification, priorKnowledge);
        }

        // 2.5) Phase-based development: for a big build the Phase Planner lays out an ordered roadmap
        //      (persisted in the repo so a future session knows what's done/pending); a continuation
        //      run advances to the next pending phase. Either way the Team Lead is scoped to ONE phase.
        Phasing phasing = resolvePhasing(projectId, request, specification, priorKnowledge);

        // 3) Team Lead decomposes the AGREED request + spec + research into a task graph.
        // The agreed spec — not the raw request — is the prominent planning text: after a mid-run
        // scope correction the spec is the live scope, and planning from the stale request text is
        // how discarded briefs end up as every downstream task's description.
        String planningInstructions = specification.isBlank()
                ? request.featureRequest()
                : "Plan against this AGREED specification. It is the live scope and supersedes the "
                        + "original request wherever they differ:\n" + specification
                        + "\n\nOriginal request (context only):\n" + request.featureRequest();
        // A phased run scopes the Team Lead to the current phase only — make that the prominent
        // instruction so the slice isn't buried in grounding (a real failure mode the retros flagged).
        if (phasing.currentPhase() != null) {
            planningInstructions = "Build ONLY " + phasing.currentPhaseHeadline()
                    + ". The full roadmap is context; do NOT build later phases.\n\n" + planningInstructions;
        }
        Agent teamLead = agentFactory.create(AgentRole.TEAM_LEAD);
        Task planningTask = newTask(TaskId.random(), "Plan project", planningInstructions, AgentRole.TEAM_LEAD);
        Map<String, String> grounding = new HashMap<>();
        if (!priorKnowledge.isBlank()) {
            grounding.put("projectKnowledge", priorKnowledge);
        }
        if (!specification.isBlank()) {
            grounding.put("specification", specification);
        }
        if (!clarified.clarifications().isBlank()) {
            grounding.put("clarifications", clarified.clarifications());
        }
        if (!marketResearch.isBlank()) {
            grounding.put("marketResearch", marketResearch);
        }
        // Tell the Team Lead whether to bother planning a knowledge-capture task (it is also
        // enforced below by stripping any curator task when remembering is off).
        grounding.put("rememberProject", Boolean.toString(rememberProject));
        // Phase-based build: show the roadmap (done/now/later) and scope the Team Lead to the
        // in-progress phase.
        if (!phasing.roadmap().isBlank()) {
            grounding.put("phasePlan", phasing.roadmap());
        }
        if (phasing.currentPhase() != null) {
            grounding.put("currentPhase", "You are building PHASE " + phasing.currentPhase().number()
                    + ": " + phasing.currentPhase().headline() + ". Plan ONLY the tasks needed to ship "
                    + "THIS phase; earlier phases are already built (see phasePlan) and later phases are "
                    + "built in their own runs.");
        }
        // Edit run: tell the Team Lead to MODIFY the existing project, and show it the current files so
        // it plans changes to real code instead of a greenfield rebuild.
        if (editMode) {
            String dir = workspaces == null ? "" : workspaces.directoryOf(projectId).orElse("");
            String files = existingFiles(projectId);
            grounding.put("editExistingProject",
                    "MODIFY the existing project at " + dir + " to satisfy the request. Build on the "
                            + "current code — do NOT recreate it from scratch. Plan only the tasks needed "
                            + "for this change."
                            + (files.isBlank() ? "" : "\nExisting files:\n" + files));
        }

        planAudit(projectId, AgentRole.TEAM_LEAD.name(), AuditLog.EventType.PROMPT,
                "TEAM_LEAD decomposing the request into tasks",
                Map.of("role", "TEAM_LEAD", "collaborator", "BUSINESS_ANALYST",
                        "prompt", planningInstructions));

        Agent.Response response = teamLead.handle(
                new Agent.Request(planningTask, planningInstructions, grounding, Map.of()),
                new Agent.Context(projectId, planningTask.id().value(), Map.of()));

        InMemoryTaskGraph graph = new InMemoryTaskGraph();
        List<Map<String, Object>> taskSpecs = extractTasks(response);
        if (!rememberProject) {
            // Enforce opt-in: drop any knowledge-capture task the Team Lead added. Nothing depends on
            // the curator (it is the terminal node), so removing it leaves no dangling dependencies.
            taskSpecs = taskSpecs.stream()
                    .filter(spec -> parseRole(spec.get("role")) != AgentRole.KNOWLEDGE_CURATOR)
                    .toList();
        }
        if (taskSpecs.isEmpty()) {
            graph.addTask(newTask(TaskId.random(), "Clarify and plan the request",
                    request.featureRequest(), AgentRole.TEAM_LEAD));
            return graph;
        }

        Map<String, TaskId> logicalToTaskId = new HashMap<>();
        for (Map<String, Object> spec : taskSpecs) {
            TaskId taskId = TaskId.random();
            String logicalId = asString(spec.get("id"), taskId.value());
            logicalToTaskId.put(logicalId, taskId);
            // A task may carry a testCommand override (e.g. the Team Lead specifies [npm, test]
            // for a QA task on a Node project); it flows into task metadata.
            Map<String, Object> metadata = new HashMap<>();
            List<?> testCommand = asList(spec.get("testCommand"));
            if (!testCommand.isEmpty()) {
                metadata.put("testCommand", testCommand.stream().map(String::valueOf).toList());
            }
            graph.addTask(newTask(taskId,
                    asString(spec.get("title"), "Task"),
                    asString(spec.get("description"), ""),
                    parseRole(spec.get("role")),
                    metadata));
        }
        for (Map<String, Object> spec : taskSpecs) {
            TaskId dependent = logicalToTaskId.get(asString(spec.get("id"), null));
            if (dependent == null) {
                continue;
            }
            for (Object dep : asList(spec.get("dependsOn"))) {
                TaskId prerequisite = logicalToTaskId.get(String.valueOf(dep));
                if (prerequisite != null) {
                    graph.addDependency(dependent, prerequisite);
                }
            }
        }
        gateQaOnAllDeliverables(graph);
        return graph;
    }

    /**
     * Verification runs last: every QA task gains a dependency on every other non-QA task (the
     * knowledge curator stays terminal), so QA never asserts about a deliverable — RUN.md, deploy
     * config, copy — that the plan schedules after it. Real runs hit exactly that contradiction:
     * QA hard-requires RUN.md while the planner put the RUN.md task downstream of QA, forcing a
     * scheduling-only NEEDS_REVIEW → fix → re-QA cycle. Edges that would close a cycle (a task the
     * Team Lead deliberately placed after QA) are skipped; existing edges are idempotent.
     */
    private static void gateQaOnAllDeliverables(InMemoryTaskGraph graph) {
        List<Task> all = List.copyOf(graph.tasks());
        for (Task qa : all) {
            if (qa.assignedRole() != AgentRole.QA_ENGINEER) {
                continue;
            }
            for (Task other : all) {
                if (other.id().equals(qa.id())
                        || other.assignedRole() == AgentRole.QA_ENGINEER
                        || other.assignedRole() == AgentRole.RUNTIME_VERIFIER
                        || other.assignedRole() == AgentRole.KNOWLEDGE_CURATOR) {
                    continue; // the runtime verifier and curator run AFTER QA, never gate it
                }
                try {
                    graph.addDependency(qa.id(), other.id());
                } catch (IllegalStateException cycleOrInvalid) {
                    // the other task (transitively) depends on QA — leave it downstream
                }
            }
        }
    }

    /**
     * The clarification loop. The Business Analyst drafts a specification; if it has open questions
     * (or returns INSUFFICIENT_INFORMATION) and a {@link ClarificationGateway} is wired, those
     * questions go to the user, the answers are folded back in as grounding, and the BA runs again.
     * Once the BA is confident, the refined understanding is shown to the user for confirmation;
     * corrections feed another round. Loops until confirmed or {@link #MAX_CLARIFICATION_ROUNDS}.
     *
     * <p>Best-effort: if the BA isn't configured, or no gateway is present (plain tests / no human),
     * it degrades to the old single forward pass on the raw request — no interaction.
     */
    private Clarified clarifyRequirements(String projectId, OrchestrationEngine.ProjectRequest request,
                                          String marketResearch, String priorKnowledge) {
        if (!agentFactory.supports(AgentRole.BUSINESS_ANALYST)) {
            return new Clarified("", "");
        }
        StringBuilder trail = new StringBuilder(); // accumulated Q&A + corrections across rounds
        String specification = "";
        int maxRounds = clarifier == null ? 1 : MAX_CLARIFICATION_ROUNDS;

        for (int round = 1; round <= maxRounds; round++) {
            Agent.Response r = runBusinessAnalyst(projectId, request, marketResearch, priorKnowledge,
                    trail.toString(), round);
            specification = stringOutput(r, "specification");
            List<String> questions = extractQuestions(r);
            boolean needsAnswers = r.outcome() == Agent.Outcome.INSUFFICIENT_INFORMATION
                    || !questions.isEmpty();

            // Ask the user the open questions and loop with their answers.
            if (clarifier != null && needsAnswers && !questions.isEmpty()) {
                String context = specification.isBlank() ? request.featureRequest() : specification;
                Optional<String> answers = safeAsk(projectId, questions, context);
                if (answers.isEmpty()) {
                    break; // no human available / declined: proceed with what we have
                }
                trail.append("Round ").append(round).append(" — questions:\n");
                for (String q : questions) {
                    trail.append("  • ").append(q).append('\n');
                }
                trail.append("User's answers: ").append(answers.get()).append("\n\n");
                continue;
            }

            // BA is confident: confirm the understanding with the user before building.
            if (clarifier != null && !specification.isBlank()) {
                ClarificationGateway.Confirmation confirmation =
                        safeConfirm(projectId, understanding(specification, marketResearch));
                if (!confirmation.confirmed() && !confirmation.corrections().isBlank()) {
                    trail.append("Round ").append(round).append(" — user corrections: ")
                            .append(confirmation.corrections()).append("\n\n");
                    continue; // refine the spec with the corrections
                }
            }
            break; // confident and confirmed, or no gateway to ask
        }
        return new Clarified(specification, trail.toString().strip());
    }

    /** Whether this project opted in to the persistent project brain (default false). */
    private static boolean rememberProject(OrchestrationEngine.ProjectRequest request) {
        Object flag = request.options().get("rememberProject");
        return flag != null && Boolean.parseBoolean(flag.toString());
    }

    /** The prior committed project brief for this session, or "" if none/disabled. */
    private static String loadPriorKnowledge(ProjectKnowledgeStore knowledge) {
        return knowledge == null ? "" : knowledge.load().orElse("");
    }

    /** True when this run edits an existing project folder (orchestrate_edit sets options.editDir). */
    private static boolean isEdit(OrchestrationEngine.ProjectRequest request) {
        Object dir = request.options().get("editDir");
        return dir != null && !dir.toString().isBlank();
    }

    /** Resolve the per-project knowledge store, opening the workspace: a fresh slug folder for a build,
     *  or the existing folder for an edit. Falls back to the legacy single store with no provider. */
    private ProjectKnowledgeStore resolveWorkspaceKnowledge(String projectId,
                                                            OrchestrationEngine.ProjectRequest request,
                                                            boolean editMode) {
        if (workspaces == null) {
            return knowledgeStore;
        }
        if (editMode) {
            return workspaces.openExisting(projectId,
                    Path.of(request.options().get("editDir").toString())).knowledge();
        }
        return workspaces.open(projectId, nameHint(request)).knowledge();
    }

    /** Folder-naming hint: the explicit projectName if the caller supplied one, else the request text. */
    private static String nameHint(OrchestrationEngine.ProjectRequest request) {
        Object name = request.options().get("projectName");
        return name != null && !name.toString().isBlank() ? name.toString() : request.featureRequest();
    }

    /** A short listing of the existing project's files, to ground an edit plan (capped). */
    private String existingFiles(String projectId) {
        if (workspaces == null) {
            return "";
        }
        try {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (String path : workspaces.get(projectId).repository().list("")) {
                if (path.startsWith(".git/")) {
                    continue;
                }
                sb.append("  - ").append(path).append('\n');
                if (++count >= 200) {
                    sb.append("  …\n");
                    break;
                }
            }
            return sb.toString().strip();
        } catch (RuntimeException e) {
            return ""; // never let file-listing break planning
        }
    }

    /** One Business Analyst pass, grounded with the market research and the running Q&amp;A trail. */
    private Agent.Response runBusinessAnalyst(String projectId, OrchestrationEngine.ProjectRequest request,
                                              String marketResearch, String priorKnowledge,
                                              String trail, int round) {
        Agent ba = agentFactory.create(AgentRole.BUSINESS_ANALYST);
        Task baTask = newTask(TaskId.random(), "Clarify requirements",
                request.featureRequest(), AgentRole.BUSINESS_ANALYST);
        Map<String, String> grounding = new HashMap<>();
        if (!priorKnowledge.isBlank()) {
            grounding.put("projectKnowledge", priorKnowledge);
        }
        if (!marketResearch.isBlank()) {
            grounding.put("marketResearch", marketResearch);
        }
        if (!trail.isBlank()) {
            grounding.put("clarifications", trail);
        }
        planAudit(projectId, AgentRole.BUSINESS_ANALYST.name(), AuditLog.EventType.PROMPT,
                "BUSINESS_ANALYST clarifying requirements (round " + round + ")",
                Map.of("role", "BUSINESS_ANALYST", "collaborator", "user",
                        "prompt", request.featureRequest()));
        Agent.Response r = ba.handle(
                new Agent.Request(baTask, request.featureRequest(), grounding, Map.of()),
                new Agent.Context(projectId, baTask.id().value(), Map.of()));
        String specText = stringOutput(r, "specification");
        planAudit(projectId, AgentRole.BUSINESS_ANALYST.name(), AuditLog.EventType.RESPONSE,
                specText.isBlank() ? "BUSINESS_ANALYST has open questions for the user"
                        : "BUSINESS_ANALYST produced a specification",
                Map.of("role", "BUSINESS_ANALYST", "detail",
                        specText.isBlank() ? "(needs clarification)" : trim(specText)));
        return r;
    }

    private Optional<String> safeAsk(String projectId, List<String> questions, String context) {
        try {
            return clarifier.ask(projectId, questions, context);
        } catch (RuntimeException e) {
            return Optional.empty(); // never let a clarification round crash planning
        }
    }

    private ClarificationGateway.Confirmation safeConfirm(String projectId, String understanding) {
        try {
            return clarifier.confirm(projectId, understanding);
        } catch (RuntimeException e) {
            return ClarificationGateway.Confirmation.approved();
        }
    }

    /** A human-readable summary of what will be built, shown to the user at the confirmation step. */
    private static String understanding(String specification, String marketResearch) {
        StringBuilder sb = new StringBuilder("Here is my understanding of what you want built:\n\n");
        sb.append(specification);
        if (!marketResearch.isBlank()) {
            // Show the full market research here — this is the understanding the user confirms, so it
            // must be complete and unabridged (the 280-char trim is only for short audit summaries).
            sb.append("\n\nInformed by market research:\n").append(marketResearch.strip());
        }
        return sb.toString();
    }

    /** Pull the BA's open questions from {@code output.questions} (strings or {question:...} objects). */
    private List<String> extractQuestions(Agent.Response response) {
        Object q = response.structuredOutput().get("questions");
        List<String> questions = new ArrayList<>();
        if (q instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object text = map.get("question");
                    if (text == null) {
                        text = map.values().stream().findFirst().orElse(null);
                    }
                    if (text != null && !text.toString().isBlank()) {
                        questions.add(text.toString());
                    }
                } else if (item != null && !item.toString().isBlank()) {
                    questions.add(item.toString());
                }
            }
        } else if (q != null && !q.toString().isBlank()) {
            questions.add(q.toString());
        }
        return questions;
    }

    private static String stringOutput(Agent.Response response, String key) {
        Object v = response.structuredOutput().get(key);
        return v == null ? "" : v.toString();
    }

    /**
     * Run the Market Researcher to study comparable tools, surface their common complaints, and
     * recommend differentiating features plus a plan to address them. Best-effort: if the role isn't
     * configured or it returns nothing, planning proceeds without it.
     */
    private String researchMarket(String projectId, OrchestrationEngine.ProjectRequest request,
                                  String priorKnowledge) {
        if (!agentFactory.supports(AgentRole.MARKET_RESEARCHER)) {
            return "";
        }
        try {
            Agent researcher = agentFactory.create(AgentRole.MARKET_RESEARCHER);
            Task task = newTask(TaskId.random(), "Research the market",
                    request.featureRequest(), AgentRole.MARKET_RESEARCHER);
            Map<String, String> grounding = priorKnowledge.isBlank()
                    ? Map.of() : Map.of("projectKnowledge", priorKnowledge);
            planAudit(projectId, AgentRole.MARKET_RESEARCHER.name(), AuditLog.EventType.PROMPT,
                    "MARKET_RESEARCHER researching comparable tools and their complaints",
                    Map.of("role", "MARKET_RESEARCHER", "collaborator", "BUSINESS_ANALYST",
                            "prompt", request.featureRequest()));
            Agent.Response r = researcher.handle(
                    new Agent.Request(task, request.featureRequest(), grounding, Map.of()),
                    new Agent.Context(projectId, task.id().value(), Map.of()));
            String research = summarizeResearch(r.structuredOutput());
            planAudit(projectId, AgentRole.MARKET_RESEARCHER.name(), AuditLog.EventType.RESPONSE,
                    "MARKET_RESEARCHER produced feature recommendations + a plan",
                    Map.of("role", "MARKET_RESEARCHER", "detail",
                            research.isBlank() ? "(no findings)" : trim(research)));
            return research;
        } catch (RuntimeException e) {
            return ""; // never let market research crash planning
        }
    }

    /** Fold the researcher's structured output into a compact text block the Team Lead can act on. */
    private static String summarizeResearch(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, output, "summary", "Summary");
        appendIfPresent(sb, output, "complaints", "Common complaints in similar tools");
        appendIfPresent(sb, output, "recommendedFeatures", "Recommended features");
        appendIfPresent(sb, output, "plan", "Plan to address them");
        return sb.toString().strip();
    }

    private static void appendIfPresent(StringBuilder sb, Map<String, Object> output,
                                        String key, String label) {
        Object value = output.get(key);
        if (value != null && !value.toString().isBlank()) {
            sb.append(label).append(": ").append(value).append('\n');
        }
    }

    @SuppressWarnings("unchecked")
    /** The phase context for this run: the roadmap to show the Team Lead, and the one phase (if any)
     *  it should build now. {@code currentPhase} null means a normal, non-phased build. */
    private record Phasing(String roadmap, PhasePlan.Phase currentPhase) {
        static Phasing none() {
            return new Phasing("", null);
        }

        String currentPhaseHeadline() {
            return currentPhase == null ? "" : "phase " + currentPhase.number() + ": "
                    + currentPhase.headline();
        }
    }

    /**
     * Work out the phase context. A continuation run (a project that already has a committed phase
     * plan) advances to the next not-done phase and marks it in progress. A fresh build asks the
     * Phase Planner for a roadmap only when {@code phased} was requested. The plan is persisted in the
     * repo, so a brand-new session sees exactly what is done and what is pending. Best-effort —
     * phasing never crashes planning.
     */
    private Phasing resolvePhasing(String projectId, OrchestrationEngine.ProjectRequest request,
                                   String specification, String priorKnowledge) {
        if (workspaces == null) {
            return Phasing.none();
        }
        PhasePlanStore store;
        try {
            store = new PhasePlanStore(workspaces.get(projectId).repository());
        } catch (RuntimeException e) {
            return Phasing.none();
        }
        try {
            Optional<PhasePlan> existing = store.load();
            if (existing.isPresent()) {
                PhasePlan plan = existing.get();
                // Only ADVANCE the roadmap when this run explicitly continues the phases
                // (orchestrate_phases build=true). A plain orchestrate_edit on a phased project must
                // NOT consume a phase — it just gets the roadmap as read-only context.
                if (!isPhaseContinue(request) && !isPhased(request)) {
                    return new Phasing(plan.groundingText(), null);
                }
                Optional<PhasePlan.Phase> next = plan.nextPending();
                if (next.isEmpty()) {
                    return new Phasing(plan.groundingText(), null); // every phase already shipped
                }
                PhasePlan advanced = plan.withStatus(next.get().number(), PhasePlan.Status.IN_PROGRESS);
                store.save(advanced, null, AgentRole.PHASE_PLANNER.name());
                return new Phasing(advanced.groundingText(),
                        next.get().withStatus(PhasePlan.Status.IN_PROGRESS));
            }
            if (!isPhased(request)) {
                return Phasing.none(); // a normal, single-shot build
            }
            PhasePlan planned = runPhasePlanner(projectId, request, specification, priorKnowledge);
            if (planned == null || planned.phases().isEmpty()) {
                return Phasing.none();
            }
            // At the START of phase 1, ask the user how to advance: roll through every phase, or pause
            // for their approval before each. The choice is persisted in the roadmap and honoured for
            // the rest of the build (and future sessions).
            boolean autonomous = askPhaseMode(projectId, planned);
            int first = planned.phases().get(0).number();
            PhasePlan started = planned.withAutonomous(autonomous)
                    .withStatus(first, PhasePlan.Status.IN_PROGRESS);
            store.save(started, null, AgentRole.PHASE_PLANNER.name());
            return new Phasing(started.groundingText(), started.phases().get(0));
        } catch (RuntimeException e) {
            return Phasing.none();
        }
    }

    /** True when this run opted into phase-based development (orchestrate_start phased=true). */
    private static boolean isPhased(OrchestrationEngine.ProjectRequest request) {
        Object flag = request.options().get("phased");
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    /**
     * Ask the user, at the start of a phased build, how to advance between phases: build them ALL
     * autonomously, or PAUSE for approval before each new phase. The answer is stored in the roadmap.
     * Defaults to autonomous when no approval channel is wired (non-interactive runs).
     */
    private boolean askPhaseMode(String projectId, PhasePlan plan) {
        if (clarifier == null) {
            return true;
        }
        String q = "This is a " + plan.phases().size() + "-phase build:\n" + plan.groundingText()
                + "\n\nHow should I proceed between phases? Reply 'autonomous' to build ALL phases "
                + "straight through without stopping, or 'pause' to stop for your approval before each "
                + "new phase.";
        Optional<String> answer = clarifier.ask(projectId, List.of(q), "phase advance mode");
        String a = answer.orElse("").toLowerCase();
        boolean wantsPause = a.contains("pause") || a.contains("approv") || a.contains("review")
                || a.contains("confirm") || a.contains("checkpoint") || a.contains("before each")
                || a.contains("stop");
        return !wantsPause; // default to autonomous unless they clearly ask to pause
    }

    /** True when this run continues a phased project's roadmap (orchestrate_phases build=true). */
    private static boolean isPhaseContinue(OrchestrationEngine.ProjectRequest request) {
        Object flag = request.options().get("phaseContinue");
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    /** One Phase Planner pass: turn the agreed scope into an ordered roadmap of shippable phases. */
    private PhasePlan runPhasePlanner(String projectId, OrchestrationEngine.ProjectRequest request,
                                      String specification, String priorKnowledge) {
        if (!agentFactory.supports(AgentRole.PHASE_PLANNER)) {
            return null;
        }
        String input = specification.isBlank() ? request.featureRequest() : specification;
        Agent planner = agentFactory.create(AgentRole.PHASE_PLANNER);
        Task task = newTask(TaskId.random(), "Plan phases", input, AgentRole.PHASE_PLANNER);
        Map<String, String> grounding = new HashMap<>();
        if (!priorKnowledge.isBlank()) {
            grounding.put("projectKnowledge", priorKnowledge);
        }
        if (!specification.isBlank()) {
            grounding.put("specification", specification);
        }
        planAudit(projectId, AgentRole.PHASE_PLANNER.name(), AuditLog.EventType.PROMPT,
                "PHASE_PLANNER breaking the project into shippable phases",
                Map.of("role", "PHASE_PLANNER", "collaborator", "TEAM_LEAD", "prompt", input));
        Agent.Response r = planner.handle(
                new Agent.Request(task, input, grounding, Map.of()),
                new Agent.Context(projectId, task.id().value(), Map.of()));
        List<PhasePlan.Phase> phases = extractPhases(r);
        if (phases.isEmpty()) {
            return null;
        }
        List<String> titles = new ArrayList<>();
        for (PhasePlan.Phase p : phases) {
            titles.add(p.number() + ". " + p.title());
        }
        planAudit(projectId, AgentRole.PHASE_PLANNER.name(), AuditLog.EventType.RESPONSE,
                "PHASE_PLANNER produced a " + phases.size() + "-phase roadmap",
                Map.of("role", "PHASE_PLANNER", "detail", trim(String.join("; ", titles))));
        return new PhasePlan(request.featureRequest(), phases);
    }

    /** Parse the Phase Planner's {@code output.phases} into an ordered, all-pending phase list. */
    private List<PhasePlan.Phase> extractPhases(Agent.Response response) {
        List<PhasePlan.Phase> phases = new ArrayList<>();
        Object raw = response.structuredOutput().get("phases");
        if (!(raw instanceof List<?> list)) {
            return phases;
        }
        int number = 1;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                String title = asString(map.get("title"), "").strip();
                String description = asString(map.get("description"), "").strip();
                if (title.isBlank() && description.isBlank()) {
                    continue;
                }
                if (title.isBlank()) {
                    title = description;
                    description = "";
                }
                phases.add(new PhasePlan.Phase(number++, title, description, PhasePlan.Status.PENDING));
            } else if (item != null && !item.toString().isBlank()) {
                phases.add(new PhasePlan.Phase(number++, item.toString().strip(), "",
                        PhasePlan.Status.PENDING));
            }
        }
        return phases;
    }

    /** A domain skill the Skill Smith proposes, pending the user's approval. */
    private record ProposedSkill(String name, String content, List<AgentRole> roles) {}

    /**
     * Run the Skill Smith to research any domain gap, then APPROVAL-GATE its proposals with the user
     * before persisting them. Approved skills are written + attached so later agents this run use them.
     * Disabled (no-op) without a SkillRegistry, a ClarificationGateway (no approval channel → never
     * attach unreviewed guidance), or a SKILL_SMITH agent. Best-effort: never crashes planning.
     */
    private void synthesizeSkills(String projectId, OrchestrationEngine.ProjectRequest request,
                                  String specification, String priorKnowledge) {
        if (skills == null || clarifier == null || !agentFactory.supports(AgentRole.SKILL_SMITH)) {
            return;
        }
        try {
            String input = specification.isBlank() ? request.featureRequest() : specification;
            Agent smith = agentFactory.create(AgentRole.SKILL_SMITH);
            Task task = newTask(TaskId.random(), "Research domain skills", input, AgentRole.SKILL_SMITH);
            Map<String, String> grounding = new HashMap<>();
            if (!priorKnowledge.isBlank()) {
                grounding.put("projectKnowledge", priorKnowledge);
            }
            if (!specification.isBlank()) {
                grounding.put("specification", specification);
            }
            grounding.put("existingSkills", String.join(", ", skills.available()));
            planAudit(projectId, AgentRole.SKILL_SMITH.name(), AuditLog.EventType.PROMPT,
                    "SKILL_SMITH checking for a domain-skill gap",
                    Map.of("role", "SKILL_SMITH", "collaborator", "user", "prompt", input));
            Agent.Response r = smith.handle(
                    new Agent.Request(task, input, grounding, Map.of()),
                    new Agent.Context(projectId, task.id().value(), Map.of()));
            List<ProposedSkill> proposed = extractSkills(r);
            if (proposed.isEmpty()) {
                return; // ordinary project — no domain skill needed
            }
            approveAndPersistSkills(projectId, proposed);
        } catch (RuntimeException e) {
            // never let skill synthesis crash planning
        }
    }

    /** Parse the Skill Smith's {@code output.skills} into proposals; a skill attached to no known
     *  role, or with a blank name/body, is dropped. */
    private List<ProposedSkill> extractSkills(Agent.Response response) {
        List<ProposedSkill> out = new ArrayList<>();
        Object raw = response.structuredOutput().get("skills");
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String name = asString(map.get("name"), "").strip();
            String content = asString(map.get("content"), "").strip();
            if (name.isBlank() || content.isBlank()) {
                continue;
            }
            List<AgentRole> roles = new ArrayList<>();
            for (Object ro : asList(map.get("roles"))) {
                try {
                    roles.add(AgentRole.valueOf(String.valueOf(ro).trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // skip unknown role names
                }
            }
            if (!roles.isEmpty()) {
                out.add(new ProposedSkill(name, content, roles));
            }
        }
        return out;
    }

    /** Present the proposed skills to the user and persist only the ones they approve. */
    private void approveAndPersistSkills(String projectId, List<ProposedSkill> proposed) {
        StringBuilder q = new StringBuilder("The team researched domain skills this build may need and "
                + "proposes adding them (each guides the listed roles for this build and future ones):\n");
        for (ProposedSkill s : proposed) {
            List<String> roleNames = s.roles().stream().map(AgentRole::name).toList();
            q.append("\n- ").append(s.name()).append(" → ").append(String.join(", ", roleNames))
                    .append("\n  ").append(firstLine(s.content()));
        }
        q.append("\n\nApprove which to add? Reply 'approve' to add all, 'reject' to add none, or name "
                + "the specific skills to add.");
        Optional<String> answer = clarifier.ask(projectId, List.of(q.toString()), "domain-skill approval");
        List<ProposedSkill> approved = decideApproved(proposed, answer.orElse(""));
        for (ProposedSkill s : approved) {
            String slug = skills.addSynthesizedSkill(s.name(), s.content(), s.roles());
            if (!slug.isBlank()) {
                planAudit(projectId, AgentRole.SKILL_SMITH.name(), AuditLog.EventType.STATE_CHANGE,
                        "Approved domain skill '" + slug + "' attached to " + s.roles(),
                        Map.of("role", "SKILL_SMITH", "detail", slug + " → " + s.roles()));
            }
        }
    }

    /** Interpret the user's free-text approval: explicit rejection → none; named skills → those; an
     *  affirmative → all; anything unclear → none (never attach unreviewed guidance). */
    private static List<ProposedSkill> decideApproved(List<ProposedSkill> proposed, String answer) {
        String a = answer == null ? "" : answer.toLowerCase().strip();
        if (a.isBlank() || a.contains("reject") || a.contains("none") || a.equals("no")
                || a.startsWith("no ") || a.contains("don't") || a.contains("do not")) {
            return List.of();
        }
        List<ProposedSkill> named = proposed.stream()
                .filter(s -> a.contains(s.name().toLowerCase())).toList();
        if (!named.isEmpty()) {
            return named;
        }
        boolean affirmative = a.contains("approve") || a.contains("all") || a.contains("yes")
                || a.equals("ok") || a.contains("add them") || a.contains("go ahead");
        return affirmative ? proposed : List.of();
    }

    private static String firstLine(String content) {
        for (String line : content.split("\n")) {
            String t = line.strip();
            if (!t.isBlank()) {
                return t.length() <= 140 ? t : t.substring(0, 140) + "…";
            }
        }
        return "";
    }

    private List<Map<String, Object>> extractTasks(Agent.Response response) {
        Object tasks = response.structuredOutput().get("tasks");
        List<Map<String, Object>> result = new ArrayList<>();
        if (tasks instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
        }
        return result;
    }

    private Task newTask(TaskId id, String title, String description, AgentRole role) {
        return newTask(id, title, description, role, Map.of());
    }

    private Task newTask(TaskId id, String title, String description, AgentRole role,
                         Map<String, Object> metadata) {
        Instant now = Instant.now();
        return new Task(id, title, description, role, WorkflowState.PENDING,
                List.of(), metadata, now, now);
    }

    private AgentRole parseRole(Object value) {
        if (value == null) {
            return AgentRole.BACKEND_DEVELOPER;
        }
        try {
            return AgentRole.valueOf(value.toString().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AgentRole.BACKEND_DEVELOPER;
        }
    }

    private static String asString(Object value, String fallback) {
        return value != null ? value.toString() : fallback;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String trim(String text) {
        String t = text.strip();
        return t.length() <= 280 ? t : t.substring(0, 280) + "…";
    }
}
