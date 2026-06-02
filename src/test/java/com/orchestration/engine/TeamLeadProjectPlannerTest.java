package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentId;
import com.orchestration.agent.AgentRole;
import com.orchestration.agent.Capability;
import com.orchestration.task.Task;
import com.orchestration.task.TaskGraph;
import com.orchestration.task.WorkflowState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamLeadProjectPlannerTest {

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
