package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentRole;
import com.orchestration.audit.AuditLog;
import com.orchestration.knowledge.ProjectKnowledgeStore;
import com.orchestration.task.InMemoryTaskGraph;
import com.orchestration.task.Task;
import com.orchestration.task.TaskGraph;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;

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
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.auditLog = auditLog;
        this.clarifier = clarifier;
        this.knowledgeStore = knowledgeStore;
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
        // Opt-in: the project brain is only worth its cost for projects you'll continue across
        // sessions. It is OFF by default, so a one-shot run pays nothing for it.
        boolean rememberProject = rememberProject(request);

        // 0) Prior project brain: if remembering is on and this repo already has a committed knowledge
        //    file, hand it to the team as context so an EDIT starts from understanding, not a re-read.
        String priorKnowledge = rememberProject ? loadPriorKnowledge() : "";

        // 1) Market Researcher studies comparable tools, their complaints, and recommends features —
        //    up front, so the questions the BA asks the user are informed by what the market shows.
        String marketResearch = researchMarket(projectId, request, priorKnowledge);

        // 2) Clarification loop: the BA drafts a spec, asks the user the open questions, folds the
        //    answers back in, and repeats until the understanding is confirmed (or the cap is hit).
        Clarified clarified = clarifyRequirements(projectId, request, marketResearch, priorKnowledge);
        String specification = clarified.specification();

        // 3) Team Lead decomposes the AGREED request + spec + research into a task graph.
        Agent teamLead = agentFactory.create(AgentRole.TEAM_LEAD);
        Task planningTask = newTask(TaskId.random(), "Plan project", request.featureRequest(), AgentRole.TEAM_LEAD);
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

        planAudit(projectId, AgentRole.TEAM_LEAD.name(), AuditLog.EventType.PROMPT,
                "TEAM_LEAD decomposing the request into tasks",
                Map.of("role", "TEAM_LEAD", "collaborator", "BUSINESS_ANALYST",
                        "prompt", request.featureRequest()));

        Agent.Response response = teamLead.handle(
                new Agent.Request(planningTask, request.featureRequest(), grounding, Map.of()),
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
        return graph;
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
    private String loadPriorKnowledge() {
        return knowledgeStore == null ? "" : knowledgeStore.load().orElse("");
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
            sb.append("\n\nInformed by market research:\n").append(trim(marketResearch));
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
