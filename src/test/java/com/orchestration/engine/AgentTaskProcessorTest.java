package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentId;
import com.orchestration.agent.AgentRole;
import com.orchestration.agent.Capability;
import com.orchestration.artifact.ArtifactRepository;
import com.orchestration.artifact.JGitArtifactRepository;
import com.orchestration.audit.InMemoryAuditLog;
import com.orchestration.knowledge.ProjectKnowledgeStore;
import com.orchestration.task.Task;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;
import com.orchestration.verify.ProjectBuildVerifier;
import com.orchestration.workspace.FileProjectWorkspaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskProcessorTest {

    @TempDir
    Path repoDir;

    /** Captures the request it receives so tests can inspect injected parameters. */
    private static Agent capturingAgent(AtomicReference<Agent.Request> sink) {
        return new Agent() {
            @Override public AgentId id() { return new AgentId("qa-1"); }
            @Override public AgentRole role() { return AgentRole.QA_ENGINEER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.RUN_TESTS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                sink.set(request);
                return new Response(Outcome.COMPLETED, Map.of(), List.of(),
                        Confidence.HIGH, List.of(), Optional.empty());
            }
        };
    }

    /** A stub agent that returns one code artifact. */
    private static Agent artifactAgent() {
        return new Agent() {
            @Override public AgentId id() { return new AgentId("agent-1"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.COMPLETED,
                        Map.of("summary", "done"),
                        List.of(new Artifact("src/A.java", "class A {}", "text/plain")),
                        Confidence.HIGH, List.of(), Optional.empty());
            }
        };
    }

    /** Factory that serves only the given agent's role, so the Prompt Engineer pre-step (which
     *  checks supports(PROMPT_ENGINEER)) is correctly skipped in these focused tests. */
    private static AgentFactory factoryReturning(Agent agent) {
        return new AgentFactory() {
            @Override public Agent create(AgentRole role) { return agent; }
            @Override public boolean supports(AgentRole role) { return role == agent.role(); }
            @Override public Set<AgentRole> supportedRoles() { return Set.of(agent.role()); }
        };
    }

    /** A Knowledge Curator that returns a brief in output.knowledge and no artifacts. */
    private static Agent curatorAgent(String brief) {
        return new Agent() {
            @Override public AgentId id() { return new AgentId("kc-1"); }
            @Override public AgentRole role() { return AgentRole.KNOWLEDGE_CURATOR; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.CURATE_KNOWLEDGE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.COMPLETED, Map.of("knowledge", brief),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
    }

    @Test
    void knowledgeCuratorOutputIsCommittedAsTheProjectBrief() {
        JGitArtifactRepository repo = new JGitArtifactRepository(repoDir);
        ProjectKnowledgeStore store = new ProjectKnowledgeStore(repo);
        String brief = "# Project brain\nDoes things. Built with Java.";
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(curatorAgent(brief)), repo, new InMemoryAuditLog(),
                ".", List.of("./gradlew", "test"), 0, store);

        Task task = new Task(new TaskId("kc"), "Curate knowledge", "write the brief",
                AgentRole.KNOWLEDGE_CURATOR, WorkflowState.PENDING, List.of(), Map.of(),
                Instant.now(), Instant.now());
        processor.process("p1", task);

        // The curator's brief is committed as the knowledge file, and a fresh store reads it back.
        assertEquals(brief, repo.read(ProjectKnowledgeStore.DEFAULT_PATH).orElseThrow());
        assertEquals(brief, new ProjectKnowledgeStore(repo).load().orElseThrow());
    }

    @Test
    void blockedWorkerAsksTheUserAndResumesWithTheAnswer() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Map<String, String>> secondGrounding = new AtomicReference<>(Map.of());
        Agent dev = new Agent() {
            @Override public AgentId id() { return new AgentId("dev-1"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                if (calls.incrementAndGet() == 1) {
                    return new Response(Outcome.INSUFFICIENT_INFORMATION,
                            Map.of("questions", List.of("Which database should I use?")),
                            List.of(), Confidence.LOW, List.of(), Optional.empty());
                }
                secondGrounding.set(request.inputArtifacts());
                return new Response(Outcome.COMPLETED, Map.of("summary", "done"),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AtomicReference<List<String>> asked = new AtomicReference<>(List.of());
        ClarificationGateway gateway = new ClarificationGateway() {
            @Override public Optional<String> ask(String projectId, List<String> questions, String ctx) {
                asked.set(List.copyOf(questions));
                return Optional.of("Use Postgres");
            }
            @Override public Confirmation confirm(String projectId, String understanding) {
                return Confirmation.approved();
            }
        };
        AgentTaskProcessor processor = new AgentTaskProcessor(factoryReturning(dev),
                new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                ".", List.of("./gradlew", "test"), 2, null, gateway);

        Task task = new Task(new TaskId("t1"), "Build", "build it", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        Agent.Response response = processor.process("p1", task);

        assertEquals(Agent.Outcome.COMPLETED, response.outcome(), "the task resolves after clarification");
        assertEquals(2, calls.get(), "the worker re-runs once with the answer");
        assertEquals(List.of("Which database should I use?"), asked.get());
        assertTrue(secondGrounding.get().getOrDefault("userClarification", "").contains("Use Postgres"),
                "the user's answer must reach the re-run as grounding");
    }

    @Test
    void blockedWorkerStaysBlockedWithNoGateway() {
        Agent dev = new Agent() {
            @Override public AgentId id() { return new AgentId("dev-2"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.INSUFFICIENT_INFORMATION,
                        Map.of("questions", List.of("Which database?")),
                        List.of(), Confidence.LOW, List.of(), Optional.empty());
            }
        };
        // No gateway wired -> behaviour is unchanged: the blocked outcome is returned as-is.
        AgentTaskProcessor processor = new AgentTaskProcessor(factoryReturning(dev),
                new JGitArtifactRepository(repoDir), new InMemoryAuditLog());

        Task task = new Task(new TaskId("t1"), "Build", "build it", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());

        assertEquals(Agent.Outcome.INSUFFICIENT_INFORMATION, processor.process("p1", task).outcome());
    }

    @Test
    void commitsArtifactsAndAuditsTheTask() {
        JGitArtifactRepository repo = new JGitArtifactRepository(repoDir);
        InMemoryAuditLog audit = new InMemoryAuditLog();
        AgentTaskProcessor processor = new AgentTaskProcessor(factoryReturning(artifactAgent()), repo, audit);

        Task task = new Task(new TaskId("t1"), "Implement", "code it", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());

        Agent.Response response = processor.process("p1", task);

        assertEquals(Agent.Outcome.COMPLETED, response.outcome());
        assertEquals("class A {}", repo.read("src/A.java").orElseThrow());
        assertFalse(audit.forTask("t1").isEmpty());
    }

    @Test
    void injectsWorkingDirAndDefaultTestCommandIntoTheRequest() {
        AtomicReference<Agent.Request> seen = new AtomicReference<>();
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(capturingAgent(seen)),
                new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                "/work/repo", List.of("npm", "test"), 0);

        Task task = new Task(new TaskId("t1"), "QA", "", AgentRole.QA_ENGINEER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        processor.process("p1", task);

        assertEquals("/work/repo", seen.get().parameters().get("workingDir"));
        assertEquals(List.of("npm", "test"), seen.get().parameters().get("testCommand"));
    }

    @Test
    void taskMetadataOverridesTheDefaultTestCommand() {
        AtomicReference<Agent.Request> seen = new AtomicReference<>();
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(capturingAgent(seen)),
                new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                "/work/repo", List.of("./gradlew", "test"), 0);

        Task task = new Task(new TaskId("t1"), "QA", "", AgentRole.QA_ENGINEER,
                WorkflowState.PENDING, List.of(), Map.of("testCommand", List.of("pytest")),
                Instant.now(), Instant.now());
        processor.process("p1", task);

        assertEquals(List.of("pytest"), seen.get().parameters().get("testCommand"));
    }

    // --- Verified build gate (mcp): the REAL build result is authoritative, not the role-played QA. ---

    private static Agent qaAgent(Agent.Outcome rolePlayedOutcome) {
        return new Agent() {
            @Override public AgentId id() { return new AgentId("qa"); }
            @Override public AgentRole role() { return AgentRole.QA_ENGINEER; }
            @Override public Set<Capability> capabilities() { return Set.of(); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(rolePlayedOutcome, Map.of("stdout", "role-played QA"),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
    }

    private AgentTaskProcessor verifyingProcessor(Path base, Agent qa, ProjectBuildVerifier verifier) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");
        workspaces.open("p1", "calc service");
        return new AgentTaskProcessor(factoryReturning(qa),
                new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                ".", List.of("./gradlew", "test"), 0, null, null, true, workspaces, verifier);
    }

    private static Task qaTask() {
        return new Task(new TaskId("t1"), "QA", "verify", AgentRole.QA_ENGINEER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
    }

    @Test
    void aPassingRealBuildMarksQaCompletedEvenIfTheAgentWouldHaveFlaggedIt(@TempDir Path base) {
        // Role-played QA would say NEEDS_REVIEW, but the real build passes — the real result wins.
        ProjectBuildVerifier green = (dir, cmd, to) ->
                new ProjectBuildVerifier.Result(true, false, 0, false, "BUILD SUCCESSFUL");
        AgentTaskProcessor processor = verifyingProcessor(base, qaAgent(Agent.Outcome.NEEDS_REVIEW), green);

        assertEquals(Agent.Outcome.COMPLETED, processor.process("p1", qaTask()).outcome());
    }

    @Test
    void aFailingRealBuildIsNeverAcceptedEvenIfTheAgentClaimsSuccess(@TempDir Path base) {
        // Role-played QA would claim COMPLETED, but the real build fails — it must NOT be accepted.
        ProjectBuildVerifier red = (dir, cmd, to) ->
                new ProjectBuildVerifier.Result(false, false, 1, false, "compile error");
        AgentTaskProcessor processor = verifyingProcessor(base, qaAgent(Agent.Outcome.COMPLETED), red);

        assertEquals(Agent.Outcome.NEEDS_REVIEW, processor.process("p1", qaTask()).outcome());
    }

    @Test
    void whenTheBuildCannotEvenRunItFallsBackToTheRolePlayedQa(@TempDir Path base) {
        // Toolchain missing (couldNotStart) — don't block on an environment issue; use the agent's verdict.
        ProjectBuildVerifier cannotStart = (dir, cmd, to) ->
                new ProjectBuildVerifier.Result(false, true, -1, false, "gradlew: not found");
        AgentTaskProcessor processor = verifyingProcessor(base, qaAgent(Agent.Outcome.COMPLETED), cannotStart);

        assertEquals(Agent.Outcome.COMPLETED, processor.process("p1", qaTask()).outcome());
    }

    // --- Hand-off truncation must be visible, not silent (option C). ---

    private static Agent specAgent(AgentRole role, String instructions) {
        return new Agent() {
            @Override public AgentId id() { return new AgentId("spec"); }
            @Override public AgentRole role() { return role; }
            @Override public Set<Capability> capabilities() { return Set.of(); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.COMPLETED, Map.of("instructions", instructions),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
    }

    @Test
    void warnsLoudlyWhenAHandoffIsTruncated() {
        InMemoryAuditLog audit = new InMemoryAuditLog();
        String oversized = "x".repeat(13_000); // over the 12,000-char hand-off cap
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(specAgent(AgentRole.BACKEND_ARCHITECT, oversized)),
                new JGitArtifactRepository(repoDir), audit);
        Task task = new Task(new TaskId("t1"), "Architect", "design it", AgentRole.BACKEND_ARCHITECT,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());

        processor.process("p1", task);

        assertTrue(audit.all().stream().anyMatch(e ->
                        e.type() == com.orchestration.audit.AuditLog.EventType.ERROR
                                && e.summary().contains("truncated")),
                "a truncated hand-off must surface a visible warning, not be dropped silently");
    }

    @Test
    void doesNotWarnForAHandoffWithinTheCap() {
        InMemoryAuditLog audit = new InMemoryAuditLog();
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(specAgent(AgentRole.BACKEND_ARCHITECT, "x".repeat(500))),
                new JGitArtifactRepository(repoDir), audit);
        Task task = new Task(new TaskId("t1"), "Architect", "design it", AgentRole.BACKEND_ARCHITECT,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());

        processor.process("p1", task);

        assertFalse(audit.all().stream().anyMatch(e -> e.summary().contains("truncated")),
                "a hand-off within the cap must not raise a truncation warning");
    }

    // --- Artifact-overwrite guard: a truncated/summary artifact must never clobber real source. ---

    /** A developer that returns one artifact at the given path with the given (possibly stub) content. */
    private static Agent devWritingArtifact(String path, String content) {
        return new Agent() {
            @Override public AgentId id() { return new AgentId("dev"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.COMPLETED, Map.of("summary", "done"),
                        List.of(new Artifact(path, content, "text/plain")),
                        Confidence.HIGH, List.of(), Optional.empty());
            }
        };
    }

    @Test
    void refusesToOverwriteRealSourceWithATruncatedArtifact() {
        JGitArtifactRepository repo = new JGitArtifactRepository(repoDir);
        InMemoryAuditLog audit = new InMemoryAuditLog();
        String realSource = "class Base {\n" + "    void work() {}\n".repeat(40) + "}\n"; // > 200 chars
        repo.write(new ArtifactRepository.WriteRequest("seed", "BACKEND_DEVELOPER", "seed",
                List.of(new ArtifactRepository.FileChange("src/base.py", realSource))));

        // The agent lists the file with a placeholder summary instead of the full content.
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(devWritingArtifact("src/base.py", "# see repo: base.py")), repo, audit);
        Task task = new Task(new TaskId("t1"), "Implement", "code it", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());

        processor.process("p1", task);

        assertEquals(realSource, repo.read("src/base.py").orElseThrow(),
                "a truncated artifact must not clobber the real, larger source file");
        assertTrue(audit.all().stream().anyMatch(e ->
                        e.type() == com.orchestration.audit.AuditLog.EventType.ERROR
                                && e.summary().contains("Refused to overwrite")),
                "the refused overwrite must surface a visible warning");
    }

    @Test
    void stillWritesAGenuineNewFileAndAFullRewrite() {
        JGitArtifactRepository repo = new JGitArtifactRepository(repoDir);
        InMemoryAuditLog audit = new InMemoryAuditLog();
        // New file: no prior content to protect, so it is written verbatim.
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(devWritingArtifact("src/new.py", "print('hello world, a real file')")),
                repo, audit);
        Task task = new Task(new TaskId("t1"), "Implement", "code it", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());

        processor.process("p1", task);

        assertEquals("print('hello world, a real file')", repo.read("src/new.py").orElseThrow());
        assertFalse(audit.all().stream().anyMatch(e -> e.summary().contains("Refused to overwrite")),
                "a genuine new file must not trip the overwrite guard");
    }

    @Test
    void excludesDependencyAndCacheDirsFromTheProjectFilesGrounding() {
        AtomicReference<Agent.Request> seen = new AtomicReference<>();
        JGitArtifactRepository repo = new JGitArtifactRepository(repoDir);
        repo.write(new ArtifactRepository.WriteRequest("seed", "BACKEND_DEVELOPER", "seed", List.of(
                new ArtifactRepository.FileChange("src/app.py", "print('real source')"),
                new ArtifactRepository.FileChange("node_modules/left-pad/index.js", "module.exports={}"),
                new ArtifactRepository.FileChange(".venv/lib/site.py", "# vendored python"),
                new ArtifactRepository.FileChange("__pycache__/app.cpython-311.pyc", "bytecode"),
                new ArtifactRepository.FileChange("package-lock.json", "{ \"lock\": true }"))));

        // LLM mode (agentsReadFilesDirectly=false) → file CONTENTS are inlined into grounding.
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(capturingAgent(seen)), repo, new InMemoryAuditLog());
        Task task = new Task(new TaskId("t1"), "QA", "verify", AgentRole.QA_ENGINEER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());

        processor.process("p1", task);

        String grounding = seen.get().inputArtifacts().getOrDefault("projectFiles", "");
        assertTrue(grounding.contains("src/app.py"), "real source must be in the grounding");
        assertFalse(grounding.contains("node_modules"), "node_modules must be excluded");
        assertFalse(grounding.contains(".venv"), ".venv must be excluded");
        assertFalse(grounding.contains("__pycache__"), "__pycache__ must be excluded");
        assertFalse(grounding.contains("package-lock.json"), "lockfiles must be excluded");
    }

    @Test
    void reworksWhenNeedsReviewThenAcceptsImprovedResult() {
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<String> feedbackSeen = new java.util.ArrayList<>();
        Agent flaky = new Agent() {
            @Override public AgentId id() { return new AgentId("dev"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                feedbackSeen.add(request.inputArtifacts().getOrDefault("reviewFeedback", "<none>"));
                // First pass needs review; second pass is good.
                if (runs.getAndIncrement() == 0) {
                    return new Response(Outcome.NEEDS_REVIEW, Map.of(), List.of(),
                            Confidence.MEDIUM, List.of(), Optional.of("incomplete: build every component"));
                }
                return new Response(Outcome.COMPLETED, Map.of(), List.of(),
                        Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(flaky), new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                ".", List.of("./gradlew", "test"), 2);

        Task task = new Task(new TaskId("t1"), "Implement", "build it", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        Agent.Response response = processor.process("p1", task);

        assertEquals(Agent.Outcome.COMPLETED, response.outcome());
        assertEquals(2, runs.get(), "should re-run once after NEEDS_REVIEW");
        // The rework pass received the reviewer's feedback as grounding.
        assertEquals("incomplete: build every component", feedbackSeen.get(1));
    }

    @Test
    void stopsReworkingAfterMaxAttempts() {
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        Agent alwaysNeedsReview = new Agent() {
            @Override public AgentId id() { return new AgentId("dev"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                runs.incrementAndGet();
                return new Response(Outcome.NEEDS_REVIEW, Map.of(), List.of(),
                        Confidence.LOW, List.of(), Optional.of("still not good"));
            }
        };
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(alwaysNeedsReview), new JGitArtifactRepository(repoDir),
                new InMemoryAuditLog(), ".", List.of("./gradlew", "test"), 2);

        Task task = new Task(new TaskId("t1"), "Implement", "build it", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        Agent.Response response = processor.process("p1", task);

        assertEquals(Agent.Outcome.NEEDS_REVIEW, response.outcome());
        assertEquals(3, runs.get(), "initial run + 2 rework attempts");
    }

    @Test
    void downstreamTaskReceivesUpstreamOutputAsGrounding() {
        // Architect produces a spec; developer (depending on it) should receive that spec.
        Agent architect = new Agent() {
            @Override public AgentId id() { return new AgentId("arch"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_ARCHITECT; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DESIGN_ARCHITECTURE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.COMPLETED, Map.of("instructions", "USE_LAYERED_ARCH"),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AtomicReference<Agent.Request> devRequest = new AtomicReference<>();
        Agent developer = new Agent() {
            @Override public AgentId id() { return new AgentId("dev"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                devRequest.set(request);
                return new Response(Outcome.COMPLETED, Map.of("summary", "built"),
                        List.of(new Artifact("A.java", "class A {}", "text/plain")),
                        Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AgentFactory factory = new AgentFactory() {
            @Override public Agent create(AgentRole role) {
                return role == AgentRole.BACKEND_ARCHITECT ? architect : developer;
            }
            @Override public boolean supports(AgentRole role) {
                return role == AgentRole.BACKEND_ARCHITECT || role == AgentRole.BACKEND_DEVELOPER;
            }
            @Override public Set<AgentRole> supportedRoles() {
                return Set.of(AgentRole.BACKEND_ARCHITECT, AgentRole.BACKEND_DEVELOPER);
            }
        };
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factory, new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                ".", List.of("./gradlew", "test"), 0);

        TaskId archId = new TaskId("arch-1");
        Task archTask = new Task(archId, "Design", "design it", AgentRole.BACKEND_ARCHITECT,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        Task devTask = new Task(new TaskId("dev-1"), "Build", "build it", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(archId), Map.of(), Instant.now(), Instant.now());

        processor.process("p1", archTask);   // records the architect's hand-off
        processor.process("p1", devTask);    // should receive it as grounding

        assertEquals("USE_LAYERED_ARCH",
                devRequest.get().inputArtifacts().get("from_BACKEND_ARCHITECT"));
    }

    @Test
    void handoffsAreScopedPerProjectAndEvictedOnCompletion() {
        Agent architect = new Agent() {
            @Override public AgentId id() { return new AgentId("arch"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_ARCHITECT; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DESIGN_ARCHITECTURE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.COMPLETED, Map.of("instructions", "USE_LAYERED_ARCH"),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AtomicReference<Agent.Request> devRequest = new AtomicReference<>();
        Agent developer = new Agent() {
            @Override public AgentId id() { return new AgentId("dev"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                devRequest.set(request);
                return new Response(Outcome.COMPLETED, Map.of("summary", "built"),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AgentFactory factory = new AgentFactory() {
            @Override public Agent create(AgentRole role) {
                return role == AgentRole.BACKEND_ARCHITECT ? architect : developer;
            }
            @Override public boolean supports(AgentRole role) {
                return role == AgentRole.BACKEND_ARCHITECT || role == AgentRole.BACKEND_DEVELOPER;
            }
            @Override public Set<AgentRole> supportedRoles() {
                return Set.of(AgentRole.BACKEND_ARCHITECT, AgentRole.BACKEND_DEVELOPER);
            }
        };
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factory, new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                ".", List.of("./gradlew", "test"), 0);

        TaskId archId = new TaskId("arch-1");
        Task archTask = new Task(archId, "Design", "design it", AgentRole.BACKEND_ARCHITECT,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        Task devTask = new Task(new TaskId("dev-1"), "Build", "build it", AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(archId), Map.of(), Instant.now(), Instant.now());

        // Another project depending on the SAME dependency id never sees p1's hand-off (isolation).
        processor.process("p1", archTask);
        processor.process("p2", devTask);
        assertNull(devRequest.get().inputArtifacts().get("from_BACKEND_ARCHITECT"),
                "a different project must not read another project's hand-offs");

        // Within p1 the downstream task does receive it...
        processor.process("p1", devTask);
        assertEquals("USE_LAYERED_ARCH",
                devRequest.get().inputArtifacts().get("from_BACKEND_ARCHITECT"));

        // ...until the project completes, which releases the board.
        processor.onProjectComplete("p1");
        processor.process("p1", devTask);
        assertNull(devRequest.get().inputArtifacts().get("from_BACKEND_ARCHITECT"),
                "completing a project must evict its hand-offs");
    }

    /** A developer that captures its request, and a Prompt Engineer that counts how often it ran. */
    private static AgentFactory peAndDeveloper(java.util.concurrent.atomic.AtomicInteger peCalls,
                                               AtomicReference<Agent.Request> devRequest) {
        Agent pe = new Agent() {
            @Override public AgentId id() { return new AgentId("pe"); }
            @Override public AgentRole role() { return AgentRole.PROMPT_ENGINEER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.REFINE_PROMPT); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                peCalls.incrementAndGet();
                return new Response(Outcome.COMPLETED, Map.of("refinedPrompt", "REFINED"),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        Agent dev = new Agent() {
            @Override public AgentId id() { return new AgentId("dev"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                devRequest.set(request);
                return new Response(Outcome.COMPLETED, Map.of("summary", "built"),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        return new AgentFactory() {
            @Override public Agent create(AgentRole role) { return role == AgentRole.PROMPT_ENGINEER ? pe : dev; }
            @Override public boolean supports(AgentRole role) {
                return role == AgentRole.PROMPT_ENGINEER || role == AgentRole.BACKEND_DEVELOPER;
            }
            @Override public Set<AgentRole> supportedRoles() {
                return Set.of(AgentRole.PROMPT_ENGINEER, AgentRole.BACKEND_DEVELOPER);
            }
        };
    }

    private Task devTask(String description) {
        return new Task(new TaskId("t1"), "Build", description, AgentRole.BACKEND_DEVELOPER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
    }

    @Test
    void promptEngineerRunsForUnderSpecifiedTasksAndItsRefinementReachesTheWorker() {
        var peCalls = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Agent.Request> devReq = new AtomicReference<>();
        AgentTaskProcessor processor = new AgentTaskProcessor(
                peAndDeveloper(peCalls, devReq), new JGitArtifactRepository(repoDir), new InMemoryAuditLog());

        processor.process("p1", devTask("build it")); // terse, under-specified

        assertEquals(1, peCalls.get(), "PE should run for a vague task");
        assertEquals("REFINED", devReq.get().inputArtifacts().get("refinedPrompt"));
    }

    @Test
    void promptEngineerIsSkippedForWellSpecifiedTasks() {
        var peCalls = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Agent.Request> devReq = new AtomicReference<>();
        AgentTaskProcessor processor = new AgentTaskProcessor(
                peAndDeveloper(peCalls, devReq), new JGitArtifactRepository(repoDir), new InMemoryAuditLog());

        String detailed = ("word ".repeat(130)).trim(); // long, self-contained description
        processor.process("p1", devTask(detailed));

        assertEquals(0, peCalls.get(), "PE should be skipped when the task is already well-specified");
        assertNull(devReq.get().inputArtifacts().get("refinedPrompt"), "no refined prompt was added");
    }

    @Test
    void handoffPassesFullUpstreamContentNotA280CharStub() {
        String longSpec = "S".repeat(600); // far longer than the old 280-char cap
        Agent architect = new Agent() {
            @Override public AgentId id() { return new AgentId("arch"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_ARCHITECT; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.DESIGN_ARCHITECTURE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                return new Response(Outcome.COMPLETED, Map.of("instructions", longSpec),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AtomicReference<Agent.Request> devReq = new AtomicReference<>();
        Agent dev = new Agent() {
            @Override public AgentId id() { return new AgentId("dev"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                devReq.set(request);
                return new Response(Outcome.COMPLETED, Map.of("summary", "built"),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AgentFactory factory = new AgentFactory() {
            @Override public Agent create(AgentRole role) {
                return role == AgentRole.BACKEND_ARCHITECT ? architect : dev;
            }
            @Override public boolean supports(AgentRole role) {
                return role == AgentRole.BACKEND_ARCHITECT || role == AgentRole.BACKEND_DEVELOPER;
            }
            @Override public Set<AgentRole> supportedRoles() {
                return Set.of(AgentRole.BACKEND_ARCHITECT, AgentRole.BACKEND_DEVELOPER);
            }
        };
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factory, new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                ".", List.of("./gradlew", "test"), 0);

        TaskId archId = new TaskId("arch-1");
        processor.process("p1", new Task(archId, "Design", "design it", AgentRole.BACKEND_ARCHITECT,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now()));
        processor.process("p1", new Task(new TaskId("dev-1"), "Build", "build it",
                AgentRole.BACKEND_DEVELOPER, WorkflowState.PENDING, List.of(archId), Map.of(),
                Instant.now(), Instant.now()));

        assertEquals(longSpec, devReq.get().inputArtifacts().get("from_BACKEND_ARCHITECT"),
                "the full upstream output must reach the downstream agent, not a truncated stub");
    }

    @Test
    void reviewerFindingsDispatchADeveloperFixInsteadOfRerunningTheReviewer() {
        var reviewerRuns = new java.util.concurrent.atomic.AtomicInteger();
        var devRuns = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Agent.Request> devReq = new AtomicReference<>();
        Agent reviewer = new Agent() {
            @Override public AgentId id() { return new AgentId("rev"); }
            @Override public AgentRole role() { return AgentRole.CODE_REVIEWER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.REVIEW_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                reviewerRuns.incrementAndGet();
                return new Response(Outcome.NEEDS_REVIEW, Map.of("findings", "duplicate message bug"),
                        List.of(), Confidence.HIGH, List.of(), Optional.of("fix the duplicate message bug"));
            }
        };
        Agent dev = new Agent() {
            @Override public AgentId id() { return new AgentId("dev"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                devRuns.incrementAndGet();
                devReq.set(request);
                return new Response(Outcome.COMPLETED, Map.of("summary", "fixed"), List.of(),
                        Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AgentFactory factory = new AgentFactory() {
            @Override public Agent create(AgentRole role) { return role == AgentRole.CODE_REVIEWER ? reviewer : dev; }
            @Override public boolean supports(AgentRole role) {
                return role == AgentRole.CODE_REVIEWER || role == AgentRole.BACKEND_DEVELOPER;
            }
            @Override public Set<AgentRole> supportedRoles() {
                return Set.of(AgentRole.CODE_REVIEWER, AgentRole.BACKEND_DEVELOPER);
            }
        };
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factory, new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                ".", List.of("./gradlew", "test"), 2);

        Task reviewTask = new Task(new TaskId("rv1"), "Review", "review the code", AgentRole.CODE_REVIEWER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        Agent.Response r = processor.process("p1", reviewTask);

        assertEquals(Agent.Outcome.COMPLETED, r.outcome(), "the review itself is complete");
        assertEquals(1, reviewerRuns.get(), "the reviewer is NOT re-run as rework");
        assertEquals(1, devRuns.get(), "a developer is dispatched to fix the findings");
        assertTrue(devReq.get().inputArtifacts().getOrDefault("reviewFindings", "").contains("duplicate message"));
    }

    @Test
    void codeReviewerReceivesTheActualCommittedFileContents() {
        JGitArtifactRepository repo = new JGitArtifactRepository(repoDir);
        repo.write(new ArtifactRepository.WriteRequest("t0", "BACKEND_DEVELOPER", "scaffold",
                List.of(new ArtifactRepository.FileChange("src/App.java", "class App { /* SECRET_MARKER */ }"))));

        AtomicReference<Agent.Request> reviewerReq = new AtomicReference<>();
        Agent reviewer = new Agent() {
            @Override public AgentId id() { return new AgentId("rev"); }
            @Override public AgentRole role() { return AgentRole.CODE_REVIEWER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.REVIEW_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                reviewerReq.set(request);
                return new Response(Outcome.COMPLETED, Map.of("summary", "looks good"), List.of(),
                        Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factoryReturning(reviewer), repo, new InMemoryAuditLog());

        processor.process("p1", new Task(new TaskId("rv1"), "Review", "review", AgentRole.CODE_REVIEWER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now()));

        String files = reviewerReq.get().inputArtifacts().getOrDefault("projectFiles", "");
        assertTrue(files.contains("SECRET_MARKER"), "the reviewer must see the real file contents");
        assertTrue(files.contains("src/App.java"), "with file paths");
    }

    @Test
    void qaFailureRedispatchesDeveloperToFixThenPasses() {
        // QA fails the build once, a developer is re-dispatched to fix it, then QA passes.
        java.util.concurrent.atomic.AtomicInteger qaRuns = new java.util.concurrent.atomic.AtomicInteger();
        Agent qa = new Agent() {
            @Override public AgentId id() { return new AgentId("qa"); }
            @Override public AgentRole role() { return AgentRole.QA_ENGINEER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.RUN_TESTS); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                if (qaRuns.getAndIncrement() == 0) {
                    return new Response(Outcome.NEEDS_REVIEW,
                            Map.of("stderr", "compile error: missing semicolon"),
                            List.of(), Confidence.HIGH, List.of(), Optional.of("build failed"));
                }
                return new Response(Outcome.COMPLETED, Map.of("summary", "all green"),
                        List.of(), Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AtomicReference<Agent.Request> fixRequest = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger devRuns = new java.util.concurrent.atomic.AtomicInteger();
        Agent developer = new Agent() {
            @Override public AgentId id() { return new AgentId("dev"); }
            @Override public AgentRole role() { return AgentRole.BACKEND_DEVELOPER; }
            @Override public Set<Capability> capabilities() { return Set.of(Capability.WRITE_CODE); }
            @Override public boolean canHandle(Task task) { return true; }
            @Override public Response handle(Request request, Context context) {
                devRuns.incrementAndGet();
                fixRequest.set(request);
                return new Response(Outcome.COMPLETED, Map.of("summary", "fixed"),
                        List.of(new Artifact("A.java", "class A {}", "text/plain")),
                        Confidence.HIGH, List.of(), Optional.empty());
            }
        };
        AgentFactory factory = new AgentFactory() {
            @Override public Agent create(AgentRole role) {
                return role == AgentRole.QA_ENGINEER ? qa : developer;
            }
            @Override public boolean supports(AgentRole role) {
                return role == AgentRole.QA_ENGINEER || role == AgentRole.BACKEND_DEVELOPER;
            }
            @Override public Set<AgentRole> supportedRoles() {
                return Set.of(AgentRole.QA_ENGINEER, AgentRole.BACKEND_DEVELOPER);
            }
        };
        AgentTaskProcessor processor = new AgentTaskProcessor(
                factory, new JGitArtifactRepository(repoDir), new InMemoryAuditLog(),
                ".", List.of("./gradlew", "test"), 2);
        Task qaTask = new Task(new TaskId("qa-1"), "Verify", "run tests", AgentRole.QA_ENGINEER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());

        Agent.Response response = processor.process("p1", qaTask);

        assertEquals(Agent.Outcome.COMPLETED, response.outcome());
        assertEquals(1, devRuns.get());                 // developer dispatched exactly once to fix
        assertEquals(2, qaRuns.get());                  // QA ran, failed, then re-verified green
        assertTrue(fixRequest.get().inputArtifacts().get("buildFailure")
                .contains("compile error: missing semicolon"));   // the failure reached the developer
    }
}
