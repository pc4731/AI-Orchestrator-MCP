package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentId;
import com.orchestration.agent.AgentRole;
import com.orchestration.agent.Capability;
import com.orchestration.agent.SkillRegistry;
import com.orchestration.phase.PhasePlan;
import com.orchestration.phase.PhasePlanStore;
import com.orchestration.task.Task;
import com.orchestration.task.TaskGraph;
import com.orchestration.task.WorkflowState;
import com.orchestration.workspace.FileProjectWorkspaces;
import com.orchestration.workspace.ProjectWorkspaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamLeadProjectPlannerTest {

    @TempDir
    Path workspaceBase;

    private static AgentFactory teamLeadReturning(Agent.Response response) {
        Agent teamLead = new Agent() {
            @Override public AgentId id() { return new AgentId("tl"); }
            @Override public AgentRole role() { return AgentRole.TEAM_LEAD; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DECOMPOSE_TASKS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) { return response; }
        };
        return new AgentFactory() {
            @Override public Agent create(AgentRole role) { return teamLead; }
            @Override public boolean supports(AgentRole role) { return role == AgentRole.TEAM_LEAD; }
            @Override public Set<AgentRole> supportedRoles() { return Set.of(AgentRole.TEAM_LEAD); }
        };
    }

    private static OrchestrationEngine.ProjectRequest request() {
        return new OrchestrationEngine.ProjectRequest("build a todo app", Map.of(), Optional.empty());
    }

    @Test
    void qaGainsADependencyOnEveryDeliverableItAssertsAbout() {
        // The retro-famous contradiction: QA hard-requires RUN.md, but the planner scheduled the
        // RUN.md docs task parallel to (or after) QA. The planner must now gate QA on every
        // deliverable, so it verifies the finished state, not a moving target.
        Map<String, Object> dev = Map.of("id", "t1", "title", "Build", "role", "BACKEND_DEVELOPER",
                "dependsOn", List.of());
        Map<String, Object> qa = Map.of("id", "t2", "title", "Verify", "role", "QA_ENGINEER",
                "dependsOn", List.of("t1"));
        Map<String, Object> docs = Map.of("id", "t3", "title", "Write RUN.md",
                "role", "FRONTEND_DEVELOPER", "dependsOn", List.of("t1"));
        Map<String, Object> curator = Map.of("id", "t4", "title", "Record brief",
                "role", "KNOWLEDGE_CURATOR", "dependsOn", List.of("t2", "t3"));
        Agent.Response response = new Agent.Response(Agent.Outcome.COMPLETED,
                Map.of("tasks", List.of(dev, qa, docs, curator)), List.of(),
                Agent.Confidence.HIGH, List.of(), Optional.empty());

        TaskGraph graph = new TeamLeadProjectPlanner(teamLeadReturning(response)).plan("p1",
                new OrchestrationEngine.ProjectRequest("build a todo app",
                        Map.of("rememberProject", "true"), Optional.empty()));

        Task qaTask = graph.tasks().stream()
                .filter(t -> t.assignedRole() == AgentRole.QA_ENGINEER).findFirst().orElseThrow();
        Task docsTask = graph.tasks().stream()
                .filter(t -> t.assignedRole() == AgentRole.FRONTEND_DEVELOPER).findFirst().orElseThrow();
        Task curatorTask = graph.tasks().stream()
                .filter(t -> t.assignedRole() == AgentRole.KNOWLEDGE_CURATOR).findFirst().orElseThrow();

        assertTrue(graph.dependencies(qaTask.id()).contains(docsTask.id()),
                "QA must run after the RUN.md docs task it asserts about");
        assertTrue(graph.dependencies(curatorTask.id()).contains(qaTask.id()),
                "the curator stays terminal — gating QA must not invert or break that edge");
    }

    @Test
    void buildsGraphFromDecompositionWithDependencies() {
        Map<String, Object> t1 = Map.of("id", "t1", "title", "Design", "role", "BACKEND_ARCHITECT",
                "dependsOn", List.of());
        Map<String, Object> t2 = Map.of("id", "t2", "title", "Build", "role", "BACKEND_DEVELOPER",
                "dependsOn", List.of("t1"));
        Agent.Response response = new Agent.Response(Agent.Outcome.COMPLETED,
                Map.of("tasks", List.of(t1, t2)), List.of(), Agent.Confidence.HIGH, List.of(), Optional.empty());

        TaskGraph graph = new TeamLeadProjectPlanner(teamLeadReturning(response)).plan("p1", request());

        assertEquals(2, graph.tasks().size());
        Task developerTask = graph.tasks().stream()
                .filter(t -> t.assignedRole() == AgentRole.BACKEND_DEVELOPER)
                .findFirst().orElseThrow();
        assertEquals(1, graph.dependencies(developerTask.id()).size());
    }

    @Test
    void businessAnalystRunsBeforeTeamLeadAndItsSpecGroundsPlanning() {
        java.util.List<AgentRole> callOrder = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> tlGrounding =
                new java.util.concurrent.atomic.AtomicReference<>(Map.of());

        Agent ba = new Agent() {
            @Override public AgentId id() { return new AgentId("ba"); }
            @Override public AgentRole role() { return AgentRole.BUSINESS_ANALYST; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.ELICIT_REQUIREMENTS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                callOrder.add(AgentRole.BUSINESS_ANALYST);
                return new Response(Outcome.COMPLETED, Map.of("specification", "SPEC-XYZ"),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        Agent tl = new Agent() {
            @Override public AgentId id() { return new AgentId("tl"); }
            @Override public AgentRole role() { return AgentRole.TEAM_LEAD; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DECOMPOSE_TASKS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                callOrder.add(AgentRole.TEAM_LEAD);
                tlGrounding.set(request.inputArtifacts());
                return new Response(Outcome.COMPLETED,
                        Map.of("tasks", List.of(Map.of("id", "t1", "title", "Build",
                                "role", "BACKEND_DEVELOPER", "dependsOn", List.of()))),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AgentFactory factory = new AgentFactory() {
            @Override public Agent create(AgentRole role) {
                return role == AgentRole.BUSINESS_ANALYST ? ba : tl;
            }
            @Override public boolean supports(AgentRole role) {
                return role == AgentRole.BUSINESS_ANALYST || role == AgentRole.TEAM_LEAD;
            }
            @Override public Set<AgentRole> supportedRoles() {
                return Set.of(AgentRole.BUSINESS_ANALYST, AgentRole.TEAM_LEAD);
            }
        };

        new TeamLeadProjectPlanner(factory).plan("p1", request());

        assertEquals(List.of(AgentRole.BUSINESS_ANALYST, AgentRole.TEAM_LEAD), callOrder);
        assertEquals("SPEC-XYZ", tlGrounding.get().get("specification"));
    }

    @Test
    void clarificationLoopAsksTheUserThenBuildsOnTheirAnswers() {
        java.util.concurrent.atomic.AtomicInteger baCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.List<String>> asked = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> tlGrounding =
                new java.util.concurrent.atomic.AtomicReference<>(Map.of());

        Agent ba = new Agent() {
            @Override public AgentId id() { return new AgentId("ba"); }
            @Override public AgentRole role() { return AgentRole.BUSINESS_ANALYST; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.ELICIT_REQUIREMENTS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                // Round 1: open question, no spec. Round 2+: spec that folds in the user's answers.
                if (baCalls.incrementAndGet() == 1) {
                    return new Response(Outcome.INSUFFICIENT_INFORMATION,
                            Map.of("questions", List.of("Which auth provider?")),
                            List.of(), Confidence.LOW, List.of(), Optional.empty());
                }
                String clar = request.inputArtifacts().getOrDefault("clarifications", "");
                return new Response(Outcome.COMPLETED, Map.of("specification", "SPEC built from: " + clar),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        Agent tl = new Agent() {
            @Override public AgentId id() { return new AgentId("tl"); }
            @Override public AgentRole role() { return AgentRole.TEAM_LEAD; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DECOMPOSE_TASKS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                tlGrounding.set(request.inputArtifacts());
                return new Response(Outcome.COMPLETED,
                        Map.of("tasks", List.of(Map.of("id", "t1", "title", "Build",
                                "role", "BACKEND_DEVELOPER", "dependsOn", List.of()))),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AgentFactory factory = baAndTeamLead(ba, tl);

        ClarificationGateway gateway = new ClarificationGateway() {
            @Override public Optional<String> ask(String projectId, List<String> questions, String context) {
                asked.add(List.copyOf(questions));
                return Optional.of("Use Google OAuth");
            }
            @Override public Confirmation confirm(String projectId, String understanding) {
                return Confirmation.approved();
            }
        };

        new TeamLeadProjectPlanner(factory, null, gateway).plan("p1", request());

        assertEquals(2, baCalls.get(), "BA should re-run after the user answers");
        assertEquals(List.of(List.of("Which auth provider?")), asked, "the open question is asked once");
        assertTrue(tlGrounding.get().getOrDefault("clarifications", "").contains("Use Google OAuth"),
                "the user's answers must reach the Team Lead's grounding");
        assertTrue(tlGrounding.get().getOrDefault("specification", "").contains("Google OAuth"),
                "the refined spec is built on the answers");
    }

    @Test
    void confirmationCorrectionsFeedAnotherClarificationRound() {
        java.util.concurrent.atomic.AtomicInteger baCalls = new java.util.concurrent.atomic.AtomicInteger();

        Agent ba = new Agent() {
            @Override public AgentId id() { return new AgentId("ba"); }
            @Override public AgentRole role() { return AgentRole.BUSINESS_ANALYST; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.ELICIT_REQUIREMENTS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                baCalls.incrementAndGet();
                return new Response(Outcome.COMPLETED, Map.of("specification", "draft spec"),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        Agent tl = teamLeadReturningOneTask();
        AgentFactory factory = baAndTeamLead(ba, tl);

        java.util.concurrent.atomic.AtomicInteger confirmCalls = new java.util.concurrent.atomic.AtomicInteger();
        ClarificationGateway gateway = new ClarificationGateway() {
            @Override public Optional<String> ask(String projectId, List<String> questions, String context) {
                return Optional.empty();
            }
            @Override public Confirmation confirm(String projectId, String understanding) {
                // Reject once with corrections, approve the second time.
                return confirmCalls.incrementAndGet() == 1
                        ? Confirmation.changesRequested("Make it multi-tenant") : Confirmation.approved();
            }
        };

        new TeamLeadProjectPlanner(factory, null, gateway).plan("p1", request());

        assertEquals(2, baCalls.get(), "rejected confirmation should trigger another BA round");
        assertEquals(2, confirmCalls.get(), "the corrected understanding is re-confirmed");
    }

    private static Agent teamLeadReturningOneTask() {
        return new Agent() {
            @Override public AgentId id() { return new AgentId("tl"); }
            @Override public AgentRole role() { return AgentRole.TEAM_LEAD; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DECOMPOSE_TASKS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.COMPLETED,
                        Map.of("tasks", List.of(Map.of("id", "t1", "title", "Build",
                                "role", "BACKEND_DEVELOPER", "dependsOn", List.of()))),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
    }

    private static AgentFactory baAndTeamLead(Agent ba, Agent tl) {
        return new AgentFactory() {
            @Override public Agent create(AgentRole role) {
                return role == AgentRole.BUSINESS_ANALYST ? ba : tl;
            }
            @Override public boolean supports(AgentRole role) {
                return role == AgentRole.BUSINESS_ANALYST || role == AgentRole.TEAM_LEAD;
            }
            @Override public Set<AgentRole> supportedRoles() {
                return Set.of(AgentRole.BUSINESS_ANALYST, AgentRole.TEAM_LEAD);
            }
        };
    }

    @Test
    void priorProjectKnowledgeIsInjectedIntoPlanningGrounding() {
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> tlGrounding =
                new java.util.concurrent.atomic.AtomicReference<>(Map.of());
        Agent tl = new Agent() {
            @Override public AgentId id() { return new AgentId("tl"); }
            @Override public AgentRole role() { return AgentRole.TEAM_LEAD; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DECOMPOSE_TASKS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                tlGrounding.set(request.inputArtifacts());
                return new Response(Outcome.COMPLETED,
                        Map.of("tasks", List.of(Map.of("id", "t1", "title", "Build",
                                "role", "BACKEND_DEVELOPER", "dependsOn", List.of()))),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AgentFactory factory = new AgentFactory() {
            @Override public Agent create(AgentRole role) { return tl; }
            @Override public boolean supports(AgentRole role) { return role == AgentRole.TEAM_LEAD; }
            @Override public Set<AgentRole> supportedRoles() { return Set.of(AgentRole.TEAM_LEAD); }
        };

        com.orchestration.knowledge.ProjectKnowledgeStore store =
                storeReturning("PRIOR-BRIEF: the app does X with Y");

        // Opt in to the project brain for this run.
        OrchestrationEngine.ProjectRequest remembered = new OrchestrationEngine.ProjectRequest(
                "build a todo app", Map.of("rememberProject", true), Optional.empty());
        new TeamLeadProjectPlanner(factory, null, null, store).plan("p1", remembered);

        assertEquals("PRIOR-BRIEF: the app does X with Y",
                tlGrounding.get().get("projectKnowledge"),
                "a remembered project must receive the prior brief as context, not re-derive it");
    }

    @Test
    void priorKnowledgeIsNotReadWhenRememberProjectIsOff() {
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> tlGrounding =
                new java.util.concurrent.atomic.AtomicReference<>(Map.of());
        Agent tl = new Agent() {
            @Override public AgentId id() { return new AgentId("tl"); }
            @Override public AgentRole role() { return AgentRole.TEAM_LEAD; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DECOMPOSE_TASKS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                tlGrounding.set(request.inputArtifacts());
                return new Response(Outcome.COMPLETED,
                        Map.of("tasks", List.of(Map.of("id", "t1", "title", "Build",
                                "role", "BACKEND_DEVELOPER", "dependsOn", List.of()))),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AgentFactory factory = new AgentFactory() {
            @Override public Agent create(AgentRole role) { return tl; }
            @Override public boolean supports(AgentRole role) { return role == AgentRole.TEAM_LEAD; }
            @Override public Set<AgentRole> supportedRoles() { return Set.of(AgentRole.TEAM_LEAD); }
        };

        // Default request: rememberProject is off, so the brief must NOT be read or injected.
        new TeamLeadProjectPlanner(factory, null, null, storeReturning("PRIOR-BRIEF")).plan("p1", request());

        assertEquals(null, tlGrounding.get().get("projectKnowledge"),
                "a one-shot run must not pay to read the brief");
        assertEquals("false", tlGrounding.get().get("rememberProject"));
    }

    @Test
    void curatorTaskIsStrippedUnlessRememberProjectIsOn() {
        Map<String, Object> build = Map.of("id", "t1", "title", "Build",
                "role", "BACKEND_DEVELOPER", "dependsOn", List.of());
        Map<String, Object> curate = Map.of("id", "t2", "title", "Curate",
                "role", "KNOWLEDGE_CURATOR", "dependsOn", List.of("t1"));
        Agent.Response response = new Agent.Response(Agent.Outcome.COMPLETED,
                Map.of("tasks", List.of(build, curate)), List.of(),
                Agent.Confidence.HIGH, List.of(), Optional.empty());
        AgentFactory factory = teamLeadReturning(response);

        // Default (off): the curator the Team Lead added is removed.
        TaskGraph offGraph = new TeamLeadProjectPlanner(factory).plan("p1", request());
        assertEquals(1, offGraph.tasks().size());
        assertTrue(offGraph.tasks().stream().noneMatch(t -> t.assignedRole() == AgentRole.KNOWLEDGE_CURATOR));

        // Opted in: the curator is kept.
        OrchestrationEngine.ProjectRequest remembered = new OrchestrationEngine.ProjectRequest(
                "build a todo app", Map.of("rememberProject", true), Optional.empty());
        TaskGraph onGraph = new TeamLeadProjectPlanner(factory).plan("p1", remembered);
        assertTrue(onGraph.tasks().stream().anyMatch(t -> t.assignedRole() == AgentRole.KNOWLEDGE_CURATOR));
    }

    /** A knowledge store whose load() yields a fixed brief (the repo is never touched). */
    private static com.orchestration.knowledge.ProjectKnowledgeStore storeReturning(String brief) {
        com.orchestration.artifact.ArtifactRepository noRepo =
                new com.orchestration.artifact.ArtifactRepository() {
                    @Override public CommitId write(WriteRequest request) { return new CommitId("x"); }
                    @Override public Optional<String> read(String path) { return Optional.empty(); }
                    @Override public List<String> list(String pathPrefix) { return List.of(); }
                };
        return new com.orchestration.knowledge.ProjectKnowledgeStore(noRepo, true, ".project/knowledge.md") {
            @Override public Optional<String> load() { return Optional.ofNullable(brief); }
        };
    }

    /** A factory wiring a Phase Planner (returns a 2-phase roadmap) and a Team Lead that records the
     *  grounding it was handed, so a test can assert what the phasing prelude injected. */
    private static AgentFactory phasePlannerAndTeamLead(AtomicReference<Map<String, String>> tlGrounding) {
        Agent planner = new Agent() {
            @Override public AgentId id() { return new AgentId("pp"); }
            @Override public AgentRole role() { return AgentRole.PHASE_PLANNER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DECOMPOSE_TASKS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.COMPLETED, Map.of("phases", List.of(
                        Map.of("title", "Foundation", "description", "schema + core"),
                        Map.of("title", "Auth", "description", "login & sessions"))),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        Agent tl = new Agent() {
            @Override public AgentId id() { return new AgentId("tl"); }
            @Override public AgentRole role() { return AgentRole.TEAM_LEAD; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DECOMPOSE_TASKS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                tlGrounding.set(request.inputArtifacts());
                return new Response(Outcome.COMPLETED,
                        Map.of("tasks", List.of(Map.of("id", "t1", "title", "Build",
                                "role", "BACKEND_DEVELOPER", "dependsOn", List.of()))),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        return new AgentFactory() {
            @Override public Agent create(AgentRole role) {
                return role == AgentRole.PHASE_PLANNER ? planner : tl;
            }
            @Override public boolean supports(AgentRole role) {
                return role == AgentRole.PHASE_PLANNER || role == AgentRole.TEAM_LEAD;
            }
            @Override public Set<AgentRole> supportedRoles() {
                return Set.of(AgentRole.PHASE_PLANNER, AgentRole.TEAM_LEAD);
            }
        };
    }

    @Test
    void phasedBuildPlansARoadmapPersistsItAndScopesTheTeamLeadToPhaseOne() {
        AtomicReference<Map<String, String>> tlGrounding = new AtomicReference<>(Map.of());
        ProjectWorkspaces workspaces =
                new FileProjectWorkspaces(workspaceBase, true, ".project/knowledge.md");
        OrchestrationEngine.ProjectRequest phased = new OrchestrationEngine.ProjectRequest(
                "build a big app", Map.of("phased", true), Optional.empty());

        new TeamLeadProjectPlanner(phasePlannerAndTeamLead(tlGrounding), null, null, null, workspaces)
                .plan("p1", phased);

        // The Team Lead is scoped to phase 1 and sees the whole roadmap as context.
        assertTrue(tlGrounding.get().getOrDefault("currentPhase", "").contains("PHASE 1"),
                "the Team Lead must be told it is building phase 1");
        assertTrue(tlGrounding.get().containsKey("phasePlan"), "the roadmap is grounding");

        // The roadmap is committed in the project repo so a future session can read it.
        PhasePlan saved = new PhasePlanStore(workspaces.get("p1").repository()).load().orElseThrow();
        assertEquals(2, saved.phases().size());
        assertEquals("Foundation", saved.phases().get(0).title());
        assertEquals(PhasePlan.Status.IN_PROGRESS, saved.phases().get(0).status());
        assertEquals(PhasePlan.Status.PENDING, saved.phases().get(1).status());
    }

    @Test
    void continuationRunAdvancesToTheNextPendingPhase() {
        AtomicReference<Map<String, String>> tlGrounding = new AtomicReference<>(Map.of());
        ProjectWorkspaces workspaces =
                new FileProjectWorkspaces(workspaceBase, true, ".project/knowledge.md");
        AgentFactory factory = phasePlannerAndTeamLead(tlGrounding);
        TeamLeadProjectPlanner planner =
                new TeamLeadProjectPlanner(factory, null, null, null, workspaces);

        // Session 1: phased build creates the roadmap and builds phase 1.
        planner.plan("p1", new OrchestrationEngine.ProjectRequest(
                "build a big app", Map.of("phased", true), Optional.empty()));
        // Simulate phase 1 finishing (the service marks it done on a successful run).
        PhasePlanStore store = new PhasePlanStore(workspaces.get("p1").repository());
        store.markStatus(1, PhasePlan.Status.DONE, "PHASE_PLANNER");

        // Session 2: a phase-continue run (orchestrate_phases build=true) advances to phase 2.
        planner.plan("p1", new OrchestrationEngine.ProjectRequest(
                "continue", Map.of("editDir", workspaces.get("p1").dir(), "phaseContinue", true),
                Optional.empty()));

        assertTrue(tlGrounding.get().getOrDefault("currentPhase", "").contains("PHASE 2"),
                "a continuation run picks up the next pending phase");
        PhasePlan after = store.load().orElseThrow();
        assertEquals(PhasePlan.Status.DONE, after.phases().get(0).status());
        assertEquals(PhasePlan.Status.IN_PROGRESS, after.phases().get(1).status());
    }

    @Test
    void plainEditOnAPhasedProjectDoesNotConsumeAPhase() {
        AtomicReference<Map<String, String>> tlGrounding = new AtomicReference<>(Map.of());
        ProjectWorkspaces workspaces =
                new FileProjectWorkspaces(workspaceBase, true, ".project/knowledge.md");
        TeamLeadProjectPlanner planner =
                new TeamLeadProjectPlanner(phasePlannerAndTeamLead(tlGrounding), null, null, null, workspaces);

        planner.plan("p1", new OrchestrationEngine.ProjectRequest(
                "build a big app", Map.of("phased", true), Optional.empty()));
        PhasePlanStore store = new PhasePlanStore(workspaces.get("p1").repository());

        // A normal edit (no phaseContinue) must NOT advance the roadmap — phase 1 stays in progress.
        planner.plan("p1", new OrchestrationEngine.ProjectRequest(
                "fix a typo", Map.of("editDir", workspaces.get("p1").dir()), Optional.empty()));

        assertTrue(tlGrounding.get().get("currentPhase") == null,
                "a plain edit must not be scoped to a phase");
        PhasePlan after = store.load().orElseThrow();
        assertEquals(PhasePlan.Status.IN_PROGRESS, after.phases().get(0).status(),
                "phase 1 must still be in progress, not silently marked done or advanced");
    }

    /** A factory wiring a Skill Smith (proposes one domain skill) and a Team Lead. */
    private static AgentFactory skillSmithAndTeamLead() {
        Agent smith = new Agent() {
            @Override public AgentId id() { return new AgentId("ss"); }
            @Override public AgentRole role() { return AgentRole.SKILL_SMITH; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.MARKET_RESEARCH); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.COMPLETED, Map.of("skills", List.of(Map.of(
                        "name", "AEM Development",
                        "content", "Build AEM components with Sling Models and HTL.",
                        "roles", List.of("FRONTEND_DEVELOPER")))),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        return new AgentFactory() {
            @Override public Agent create(AgentRole role) {
                return role == AgentRole.SKILL_SMITH ? smith : teamLeadReturningOneTask();
            }
            @Override public boolean supports(AgentRole role) {
                return role == AgentRole.SKILL_SMITH || role == AgentRole.TEAM_LEAD;
            }
            @Override public Set<AgentRole> supportedRoles() {
                return Set.of(AgentRole.SKILL_SMITH, AgentRole.TEAM_LEAD);
            }
        };
    }

    private static ClarificationGateway gatewayAnswering(String answer) {
        return new ClarificationGateway() {
            @Override public Optional<String> ask(String projectId, List<String> questions, String ctx) {
                return Optional.ofNullable(answer);
            }
            @Override public Confirmation confirm(String projectId, String understanding) {
                return Confirmation.approved();
            }
        };
    }

    @Test
    void approvedDomainSkillIsResearchedThenAttachedToItsRole(@TempDir Path skillsDir) {
        SkillRegistry registry = new SkillRegistry(skillsDir);
        TeamLeadProjectPlanner planner = new TeamLeadProjectPlanner(
                skillSmithAndTeamLead(), null, gatewayAnswering("approve"), null, null, registry);

        planner.plan("p1", request());

        // The user approved, so the researched skill is persisted and attached to the role that needs it.
        assertTrue(registry.resolveForRole(AgentRole.FRONTEND_DEVELOPER, List.of()).contains("Sling Models"),
                "an approved domain skill must reach the role it was attached to");
        assertEquals(List.of("aem-development"), registry.attachedNames(AgentRole.FRONTEND_DEVELOPER));
    }

    @Test
    void rejectedDomainSkillIsNotAttached(@TempDir Path skillsDir) {
        SkillRegistry registry = new SkillRegistry(skillsDir);
        TeamLeadProjectPlanner planner = new TeamLeadProjectPlanner(
                skillSmithAndTeamLead(), null, gatewayAnswering("reject"), null, null, registry);

        planner.plan("p1", request());

        assertTrue(registry.attachedNames(AgentRole.FRONTEND_DEVELOPER).isEmpty(),
                "a rejected skill must never be attached");
        assertFalse(registry.resolveForRole(AgentRole.FRONTEND_DEVELOPER, List.of()).contains("Sling Models"));
    }

    @Test
    void domainSkillSynthesisIsSkippedWithoutAnApprovalGateway() {
        SkillRegistry registry = new SkillRegistry(Path.of("__unused__"));
        // No ClarificationGateway → no approval channel → synthesis must not run or attach anything.
        new TeamLeadProjectPlanner(skillSmithAndTeamLead(), null, null, null, null, registry)
                .plan("p1", request());
        assertTrue(registry.attachedNames(AgentRole.FRONTEND_DEVELOPER).isEmpty());
    }

    @Test
    void fallsBackToASingleTeamLeadTaskWhenNoTasksReturned() {
        Agent.Response response = new Agent.Response(Agent.Outcome.INSUFFICIENT_INFORMATION,
                Map.of(), List.of(), Agent.Confidence.LOW, List.of(), Optional.of("too vague"));

        TaskGraph graph = new TeamLeadProjectPlanner(teamLeadReturning(response)).plan("p1", request());

        assertEquals(1, graph.tasks().size());
        assertEquals(AgentRole.TEAM_LEAD, graph.tasks().iterator().next().assignedRole());
        assertEquals(WorkflowState.PENDING, graph.tasks().iterator().next().state());
    }
}
