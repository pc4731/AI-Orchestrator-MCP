package com.orchestration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentPrompts;
import com.orchestration.agent.AgentRole;
import com.orchestration.agent.SkillRegistry;
import com.orchestration.audit.AuditLog;
import com.orchestration.learning.Lesson;
import com.orchestration.learning.LessonExtractor;
import com.orchestration.learning.LessonPack;
import com.orchestration.learning.LessonStore;
import com.orchestration.metrics.MetricsCalculator;
import com.orchestration.metrics.MetricsStore;
import com.orchestration.metrics.RoleMetrics;
import com.orchestration.metrics.RunMetrics;
import com.orchestration.artifact.JGitArtifactRepository;
import com.orchestration.phase.PhasePlan;
import com.orchestration.phase.PhasePlanStore;

import java.nio.file.Path;
import java.time.Instant;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.feedback.FeedbackReporter;
import com.orchestration.knowledge.ProjectKnowledgeStore;
import com.orchestration.memory.MemoryStore;
import com.orchestration.task.GraphSnapshot;
import com.orchestration.web.ActiveProject;
import com.orchestration.workspace.ProjectWorkspaces;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements the four MCP tools that let Claude Code drive the agent team. Bridges the JSON-RPC
 * layer to the orchestration engine and the {@link McpBridge}; holds no transport concerns so it is
 * directly unit-testable (a test can play the role of Claude Code by calling these methods).
 */
public class OrchestrationMcpService {

    private static final long POLL_MILLIS = 4000;
    private static final long START_TIMEOUT_MILLIS = 15000;

    private final OrchestrationEngine engine;
    private final McpBridge bridge;
    private final MemoryStore memoryStore;
    private final ActiveProject activeProject;
    private final ProjectKnowledgeStore knowledgeStore; // optional; used by explain(remember=true)
    private final String workspaceDir;
    private final FeedbackReporter feedbackReporter; // optional; delivers the end-of-run retrospective
    private final ProjectWorkspaces workspaces; // optional; per-project folders (else single workspaceDir)
    private final AuditLog auditLog; // optional; source for the per-run quality tally + lessons
    private final MetricsStore metricsStore; // optional; persists the tally so trends survive restarts
    private final LessonStore lessonStore; // optional; the proposals inbox for the learning loop
    private final SkillRegistry skills; // optional; promotes an approved lesson to a learned skill
    private final McpResponseMapper mapper = new McpResponseMapper();

    // Projects whose end-of-run tally / lessons have already been written, so a repeated STOP poll
    // records once.
    private final Set<String> metricsRecorded = ConcurrentHashMap.newKeySet();
    private final Set<String> lessonsRecorded = ConcurrentHashMap.newKeySet();
    // Projects whose in-progress phase has been marked done for this run, so a repeated DONE poll
    // advances the roadmap once.
    private final Set<String> phaseMarked = ConcurrentHashMap.newKeySet();

    private volatile String activeProjectId;
    // taskId -> rememberProject, for one-off "explain a prebuilt project" tasks (not engine tasks).
    private final Map<String, Boolean> explainTasks = new ConcurrentHashMap<>();
    // End-of-run retrospective state for the active project.
    private volatile boolean retrospectiveEnabled;
    private volatile boolean retrospectiveDelivered;
    private volatile String retrospectiveTaskId;

    public OrchestrationMcpService(OrchestrationEngine engine, McpBridge bridge,
                                   MemoryStore memoryStore, ActiveProject activeProject) {
        this(engine, bridge, memoryStore, activeProject, null, ".", null);
    }

    public OrchestrationMcpService(OrchestrationEngine engine, McpBridge bridge,
                                   MemoryStore memoryStore, ActiveProject activeProject,
                                   ProjectKnowledgeStore knowledgeStore, String workspaceDir) {
        this(engine, bridge, memoryStore, activeProject, knowledgeStore, workspaceDir, null);
    }

    public OrchestrationMcpService(OrchestrationEngine engine, McpBridge bridge,
                                   MemoryStore memoryStore, ActiveProject activeProject,
                                   ProjectKnowledgeStore knowledgeStore, String workspaceDir,
                                   FeedbackReporter feedbackReporter) {
        this(engine, bridge, memoryStore, activeProject, knowledgeStore, workspaceDir,
                feedbackReporter, null);
    }

    public OrchestrationMcpService(OrchestrationEngine engine, McpBridge bridge,
                                   MemoryStore memoryStore, ActiveProject activeProject,
                                   ProjectKnowledgeStore knowledgeStore, String workspaceDir,
                                   FeedbackReporter feedbackReporter, ProjectWorkspaces workspaces) {
        this(engine, bridge, memoryStore, activeProject, knowledgeStore, workspaceDir,
                feedbackReporter, workspaces, null, null);
    }

    public OrchestrationMcpService(OrchestrationEngine engine, McpBridge bridge,
                                   MemoryStore memoryStore, ActiveProject activeProject,
                                   ProjectKnowledgeStore knowledgeStore, String workspaceDir,
                                   FeedbackReporter feedbackReporter, ProjectWorkspaces workspaces,
                                   AuditLog auditLog, MetricsStore metricsStore) {
        this(engine, bridge, memoryStore, activeProject, knowledgeStore, workspaceDir,
                feedbackReporter, workspaces, auditLog, metricsStore, null, null);
    }

    public OrchestrationMcpService(OrchestrationEngine engine, McpBridge bridge,
                                   MemoryStore memoryStore, ActiveProject activeProject,
                                   ProjectKnowledgeStore knowledgeStore, String workspaceDir,
                                   FeedbackReporter feedbackReporter, ProjectWorkspaces workspaces,
                                   AuditLog auditLog, MetricsStore metricsStore,
                                   LessonStore lessonStore, SkillRegistry skills) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
        this.activeProject = Objects.requireNonNull(activeProject, "activeProject");
        this.knowledgeStore = knowledgeStore;
        this.workspaceDir = (workspaceDir == null || workspaceDir.isBlank()) ? "." : workspaceDir;
        this.feedbackReporter = feedbackReporter;
        this.workspaces = workspaces;
        this.auditLog = auditLog;
        this.metricsStore = metricsStore;
        this.lessonStore = lessonStore;
        this.skills = skills;
    }

    /** Where the active project's code lives — its own Desktop folder when per-project workspaces are
     *  wired, else the single shared workspace directory. Used only for user-facing messages. */
    private String projectDir() {
        if (workspaces != null && activeProjectId != null) {
            return workspaces.directoryOf(activeProjectId).orElse(workspaceDir);
        }
        return workspaceDir;
    }

    /** The dashboard should stop following a project once it has stopped (DONE/FAILED/blocked). */
    private void releaseActiveFollow() {
        activeProject.clear();
    }

    /**
     * Persist this finished run's quality tally to the trend log — once per project. Counts rework,
     * build-fix iterations, failed builds and per-role outcomes from the audit log, so you can see
     * whether the agents are improving across projects. Best-effort; never breaks the loop.
     */
    private void recordMetricsOnce(String state) {
        if (auditLog == null || metricsStore == null || activeProjectId == null) {
            return;
        }
        if (!metricsRecorded.add(activeProjectId)) {
            return; // already recorded (a repeated STOP poll)
        }
        try {
            metricsStore.record(MetricsCalculator.summarize(
                    auditLog.forProject(activeProjectId), activeProjectId, state));
        } catch (RuntimeException e) {
            metricsRecorded.remove(activeProjectId); // allow a later retry
        }
    }

    /**
     * On a successful finish, mark this run's in-progress phase DONE in the committed roadmap — once
     * per project — so the next session sees it completed and builds the next pending phase. Best-effort
     * and a no-op for non-phased projects (no roadmap on disk); never breaks the loop.
     */
    private void markActivePhaseDone() {
        if (workspaces == null || activeProjectId == null || !phaseMarked.add(activeProjectId)) {
            return;
        }
        try {
            PhasePlanStore store = new PhasePlanStore(workspaces.get(activeProjectId).repository());
            Optional<PhasePlan> plan = store.load();
            if (plan.isEmpty()) {
                return; // not a phased project
            }
            plan.get().inProgress().ifPresent(p ->
                    store.markStatus(p.number(), PhasePlan.Status.DONE, AgentRole.PHASE_PLANNER.name()));
        } catch (RuntimeException e) {
            phaseMarked.remove(activeProjectId); // allow a later retry
        }
    }

    /**
     * Mine this finished run for evidence-backed lesson PROPOSALS and park them in the inbox — once per
     * project. Nothing here changes agent behavior; proposals stay PENDING until the user approves them
     * via {@code orchestrate_review_lessons}. Best-effort; never breaks the loop.
     */
    private void extractLessonsOnce(String state) {
        if (auditLog == null || lessonStore == null || activeProjectId == null) {
            return;
        }
        if (!lessonsRecorded.add(activeProjectId)) {
            return;
        }
        try {
            for (Lesson lesson : LessonExtractor.extract(auditLog.forProject(activeProjectId), activeProjectId)) {
                lessonStore.record(lesson);
            }
        } catch (RuntimeException e) {
            lessonsRecorded.remove(activeProjectId); // allow a later retry
        }
    }

    /** The pending lesson proposals awaiting your decision. Approving one (via {@link #decideLesson})
     *  turns it into a learned skill that improves that role on future runs — nothing applies until then. */
    public Map<String, Object> reviewLessons() {
        if (lessonStore == null) {
            return Map.of("error", "the learning loop is not available in this configuration");
        }
        List<Lesson> pending = lessonStore.pending();
        List<Map<String, Object>> prunable = pruneSuggestions();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", pending);
        result.put("pruneSuggestions", prunable);
        result.put("nextAction", pending.isEmpty() && prunable.isEmpty() ? "STOP" : "ASK_USER");
        result.put("message", pending.isEmpty()
                ? "No lessons pending review."
                : "These are evidence-backed improvements proposed from past runs. Show them to the USER "
                        + "and, for each, call orchestrate_review_lessons with action=approve|reject (and "
                        + "an optional edited 'text'). Approving promotes it to a learned skill for that "
                        + "role; NOTHING changes agent behavior until the user approves. Do NOT decide for them.");
        return result;
    }

    /**
     * Apply the user's decision on a proposal. {@code approve} promotes it (optionally with edited
     * {@code text}) into the role's learned skill so it shapes future runs; {@code reject} archives it.
     */
    public Map<String, Object> decideLesson(String id, String action, String text) {
        if (lessonStore == null) {
            return Map.of("error", "the learning loop is not available in this configuration");
        }
        if (id == null || id.isBlank()) {
            return Map.of("error", "lesson id is required");
        }
        Optional<Lesson> found = lessonStore.get(id);
        if (found.isEmpty()) {
            return Map.of("error", "unknown lesson id: " + id);
        }
        Lesson lesson = found.get();
        String act = action == null ? "" : action.trim().toLowerCase();
        if ("reject".equals(act)) {
            lessonStore.decide(id, Lesson.REJECTED);
            return Map.of("accepted", true, "outcome", "REJECTED",
                    "message", "Lesson rejected; it won't be proposed again.");
        }
        if (!"approve".equals(act)) {
            return Map.of("error", "action must be 'approve' or 'reject'");
        }
        if (skills == null) {
            return Map.of("error", "cannot apply approvals: no skill registry configured");
        }
        AgentRole role;
        try {
            role = AgentRole.valueOf(lesson.role());
        } catch (IllegalArgumentException e) {
            return Map.of("error", "lesson targets an unknown role: " + lesson.role());
        }
        String lessonText = text != null && !text.isBlank() ? text.trim() : lesson.lesson();
        try {
            skills.promoteLearned(role, lessonText);
        } catch (RuntimeException e) {
            return Map.of("error", "failed to write the learned skill: " + e.getMessage());
        }
        lessonStore.decide(id, Lesson.APPROVED);
        return Map.of(
                "accepted", true,
                "outcome", "APPROVED",
                "role", role.name(),
                "message", "Approved. It is now a learned skill for " + role + " and will shape that "
                        + "role on future runs (committed under config/skills/learned/" + role + ".md).");
    }

    /** Where exported lessons packs are written / read from. */
    private static final Path PACKS_DIR = Path.of("learning/packs");
    /** A role's learned skills above this size are flagged for pruning (keeps prompts/context small). */
    private static final int LEARNED_SIZE_CAP = 12_000;
    /** Recent rework+needs-review at/above this (over the last few runs) flags a learned role for review. */
    private static final int PRUNE_REWORK_THRESHOLD = 4;
    private static final int PRUNE_LOOKBACK_RUNS = 5;

    /**
     * Export all approved learned skills to a portable pack file so they can be carried to a separate
     * install (git already carries them between machines that share this repo).
     */
    public Map<String, Object> exportLessons(String name) {
        if (skills == null) {
            return Map.of("error", "the learning loop is not available in this configuration");
        }
        Map<AgentRole, String> learned = skills.learnedSkills();
        if (learned.isEmpty()) {
            return Map.of("nextAction", "STOP", "message", "No learned skills to export yet.");
        }
        List<LessonPack.Entry> entries = learned.entrySet().stream()
                .map(e -> new LessonPack.Entry(e.getKey().name(), e.getValue())).toList();
        String fileName = (name == null || name.isBlank()
                ? "lessons-" + System.currentTimeMillis()
                : name.trim().replaceAll("[^a-zA-Z0-9._-]+", "-")) + ".json";
        Path out = PACKS_DIR.resolve(fileName);
        try {
            LessonPack.write(out, new LessonPack.Bundle(Instant.now().toString(), "export", entries));
        } catch (java.io.IOException e) {
            return Map.of("error", "could not write the pack: " + e.getMessage());
        }
        return Map.of("accepted", true, "file", out.toString(), "count", entries.size(),
                "message", "Exported " + entries.size() + " learned skill(s) to " + out
                        + ". Copy this file to another install and import it there.");
    }

    /**
     * Import a lessons pack. Each learned skill is re-staged as a PENDING proposal — it must be approved
     * here before it changes anything, so importing from elsewhere is never blindly trusted.
     */
    public Map<String, Object> importLessons(String path) {
        if (lessonStore == null) {
            return Map.of("error", "the learning loop is not available in this configuration");
        }
        if (path == null || path.isBlank()) {
            return Map.of("error", "path to a lessons pack (.json) is required");
        }
        LessonPack.Bundle bundle;
        try {
            bundle = LessonPack.read(Path.of(path.trim()));
        } catch (java.io.IOException e) {
            return Map.of("error", "could not read the pack at " + path + ": " + e.getMessage());
        }
        int staged = 0;
        for (LessonPack.Entry entry : bundle.lessons()) {
            lessonStore.record(new Lesson(UUID.randomUUID().toString(), "import", entry.role(),
                    Lesson.IMPORTED, entry.content(), "Imported from " + path, 1,
                    Lesson.PENDING, Instant.now().toString()));
            staged++;
        }
        return Map.of("accepted", true, "staged", staged,
                "nextAction", staged > 0 ? "ASK_USER" : "STOP",
                "message", "Imported " + staged + " lesson(s) as PENDING proposals — review and approve "
                        + "them with orchestrate_review_lessons before they take effect.");
    }

    /** Remove a role's learned skills (gated decay): it reverts to its base behavior next run. */
    public Map<String, Object> pruneLearned(String roleName) {
        if (skills == null) {
            return Map.of("error", "the learning loop is not available in this configuration");
        }
        if (roleName == null || roleName.isBlank()) {
            return Map.of("error", "a role is required to prune");
        }
        AgentRole role;
        try {
            role = AgentRole.valueOf(roleName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Map.of("error", "unknown role: " + roleName);
        }
        if (!skills.hasLearned(role)) {
            return Map.of("error", "no learned skills to prune for " + role);
        }
        try {
            skills.clearLearned(role);
        } catch (RuntimeException e) {
            return Map.of("error", "failed to prune: " + e.getMessage());
        }
        return Map.of("accepted", true, "outcome", "PRUNED", "role", role.name(),
                "message", "Removed learned skills for " + role + "; it reverts to base behavior next run.");
    }

    /**
     * Decay signal: roles whose learned skills aren't earning their keep — either grown past the size
     * cap, or still showing high rework over recent runs despite having learned skills. Surfaced (not
     * acted on) so the user can choose to prune via {@code orchestrate_review_lessons action=prune}.
     */
    private List<Map<String, Object>> pruneSuggestions() {
        if (skills == null) {
            return List.of();
        }
        List<RunMetrics> recent = metricsStore == null ? List.of() : metricsStore.recent(PRUNE_LOOKBACK_RUNS);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentRole role : AgentRole.values()) {
            if (!skills.hasLearned(role)) {
                continue;
            }
            int size = skills.learnedSize(role);
            if (size > LEARNED_SIZE_CAP) {
                out.add(Map.of("role", role.name(), "action", "prune",
                        "reason", "learned skills are large (" + size + " chars) — prune or consolidate "
                                + "to keep the prompt small"));
                continue;
            }
            int rework = recent.stream().mapToInt(m -> roleRework(m, role.name())).sum();
            if (!recent.isEmpty() && rework >= PRUNE_REWORK_THRESHOLD) {
                out.add(Map.of("role", role.name(), "action", "prune",
                        "reason", "still high rework (" + rework + " over last " + recent.size()
                                + " runs) despite learned skills — revise or prune"));
            }
        }
        return out;
    }

    private static int roleRework(RunMetrics m, String roleName) {
        RoleMetrics r = m.byRole() == null ? null : m.byRole().get(roleName);
        return r == null ? 0 : r.rework() + r.needsReview();
    }

    /**
     * The quality tally for the current run plus the recent trend — how often the team needed rework,
     * build fixes, and clarifications, broken down by role. The numbers should fall over time as the
     * agents improve. Available to Claude via {@code orchestrate_metrics}.
     */
    public Map<String, Object> metrics() {
        if (auditLog == null) {
            return Map.of("error", "metrics are not available in this configuration");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (activeProjectId != null) {
            result.put("currentRun", MetricsCalculator.summarize(
                    auditLog.forProject(activeProjectId), activeProjectId, currentState()));
        }
        result.put("recentRuns", metricsStore == null ? List.of() : metricsStore.recent(20));
        result.put("message", "reworkDispatches, buildFixDispatches and buildsFailed should trend DOWN "
                + "across runs as the team improves; byRole shows which role is carrying the rework.");
        return result;
    }

    /** Start a project from a feature request; returns once the first agent task is ready. */
    public Map<String, Object> start(String featureRequest) {
        return start(featureRequest, null, false, false);
    }

    public Map<String, Object> start(String featureRequest, boolean rememberProject) {
        return start(featureRequest, null, rememberProject, false);
    }

    public Map<String, Object> start(String featureRequest, boolean rememberProject,
                                     boolean retrospective) {
        return start(featureRequest, null, rememberProject, retrospective);
    }

    /**
     * Start a project. {@code projectName} names the folder it is created in (defaults to a slug of the
     * request when blank); {@code rememberProject} opts in to the persistent project brain; {@code
     * retrospective} opts in to the end-of-run analysis of friction with the orchestrator, which is
     * delivered to the maintainer (email, else the backlog file).
     */
    public Map<String, Object> start(String featureRequest, String projectName,
                                     boolean rememberProject, boolean retrospective) {
        return start(featureRequest, projectName, rememberProject, retrospective, false);
    }

    /**
     * Start a project, with {@code createNew} controlling name-collision behaviour: when false (default)
     * and {@code projectName} already names an existing project folder, returns a choice prompt asking
     * whether to edit it in place or build a separate fresh copy — instead of silently suffixing a
     * {@code -2} folder. Pass {@code createNew=true} to confirm the fresh copy.
     */
    public Map<String, Object> start(String featureRequest, String projectName,
                                     boolean rememberProject, boolean retrospective, boolean createNew) {
        return start(featureRequest, projectName, rememberProject, retrospective, createNew, false);
    }

    /**
     * Start a project, with {@code phased} enabling phase-based development for a big build: the Phase
     * Planner first lays out an ordered roadmap (persisted in the repo so a later session knows what is
     * done and pending), this run builds only phase 1, and each remaining phase is built later via
     * {@code orchestrate_phases build=true}.
     */
    public Map<String, Object> start(String featureRequest, String projectName,
                                     boolean rememberProject, boolean retrospective, boolean createNew,
                                     boolean phased) {
        if (featureRequest == null || featureRequest.isBlank()) {
            return Map.of("error", "featureRequest is required");
        }
        // Collision guard: a named build over an existing project used to silently fork a "-2" copy.
        // Surface the conflict so the user chooses edit-in-place vs a deliberate fresh copy.
        if (!createNew && workspaces != null && projectName != null && !projectName.isBlank()) {
            Optional<java.nio.file.Path> existing = workspaces.existingForName(projectName);
            if (existing.isPresent()) {
                java.nio.file.Path dir = existing.get();
                return Map.of(
                        "needsChoice", true,
                        "nextAction", "ASK_USER",
                        "existingPath", dir.toString(),
                        "message", "A project named '" + projectName + "' already exists at " + dir
                                + ". Ask the user which they want, then call the matching tool: "
                                + "(a) EDIT the existing code in place — call orchestrate_edit with project=\""
                                + dir.getFileName() + "\" and their change request; or (b) build a SEPARATE "
                                + "fresh copy — call orchestrate_start again with createNew=true. Do NOT "
                                + "guess; silently creating a numbered copy was the old wrong behaviour.");
            }
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("rememberProject", rememberProject || phased); // phases need the cross-session brain
        if (projectName != null && !projectName.isBlank()) {
            options.put("projectName", projectName.trim());
        }
        if (phased) {
            options.put("phased", true);
        }
        String projectId = launch(featureRequest, options, retrospective);
        if (projectId == null) {
            return Map.of("error", "timed out starting project");
        }
        String phaseNote = phased
                ? "This is a PHASE-BASED build: the Phase Planner laid out a roadmap (saved in the "
                        + "project so a future session knows what's done/pending) and THIS run builds only "
                        + "phase 1. After it finishes, build the next phase with orchestrate_phases "
                        + "build=true, or view progress any time with orchestrate_phases. "
                : "";
        return Map.of(
                "projectId", projectId,
                "nextAction", "CALL_NEXT",
                "message", phaseNote + "Project started in " + projectDir() + ". Run the loop AUTONOMOUSLY: call "
                        + "orchestrate_next, act as the agent it returns, call orchestrate_submit, and "
                        + "repeat until nextAction is STOP. The team may pause to ask the USER clarifying "
                        + "questions or to confirm its understanding. These are answerable in BOTH the web "
                        + "dashboard (by voice or text) AND here: when you are idle, orchestrate_next "
                        + "returns nextAction ASK_USER — relay it to the user and submit ONLY their real "
                        + "answer. Whichever channel answers first wins; if the user used the dashboard, "
                        + "your submit will say it is already completed — just call orchestrate_next again. "
                        + "NEVER answer a user question yourself.");
    }

    /**
     * Edit an EXISTING project: resolve it by name (under the workspace base dir) or path, then run the
     * normal build loop pointed at that folder so the team modifies the current code in place. When the
     * reference matches more than one project, returns a choice prompt instead of guessing.
     */
    public Map<String, Object> edit(String project, String changeRequest, boolean retrospective) {
        if (workspaces == null) {
            return Map.of("error", "Editing existing projects is only available under the per-project "
                    + "workspace feature (mcp profile).");
        }
        if (project == null || project.isBlank()) {
            return Map.of("error", "project (a name or path) is required");
        }
        if (changeRequest == null || changeRequest.isBlank()) {
            return Map.of("error", "changeRequest is required — describe what to change");
        }
        List<java.nio.file.Path> matches = workspaces.resolveExisting(project);
        if (matches.isEmpty()) {
            return Map.of("nextAction", "STOP",
                    "error", "No existing project matches '" + project + "'. Use orchestrate_start to "
                            + "create it, or pass the full path to its folder.");
        }
        if (matches.size() > 1) {
            List<String> names = matches.stream()
                    .map(p -> p.getFileName().toString()).toList();
            return Map.of(
                    "needsChoice", true,
                    "candidates", names,
                    "nextAction", "ASK_USER",
                    "message", "Multiple projects match '" + project + "': " + String.join(", ", names)
                            + ". Ask the user which one to edit, then call orchestrate_edit again with the "
                            + "EXACT folder name shown (or the full path).");
        }
        java.nio.file.Path dir = matches.get(0);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("editDir", dir.toString());
        options.put("rememberProject", true); // an edit always reads the existing project's context
        String projectId = launch(changeRequest, options, retrospective);
        if (projectId == null) {
            return Map.of("error", "timed out starting the edit");
        }
        return Map.of(
                "projectId", projectId,
                "nextAction", "CALL_NEXT",
                "message", "Editing existing project at " + dir + ". The team will MODIFY the current "
                        + "code to satisfy your change request — run the loop AUTONOMOUSLY (orchestrate_next "
                        + "→ act → orchestrate_submit) until nextAction is STOP, exactly like a build.");
    }

    /**
     * View or advance a project's phase roadmap. With {@code build=false} (default) it REPORTS the
     * roadmap — what is done and what is pending — which is how a brand-new session learns where the
     * project stands. With {@code build=true} it starts a run that builds the next pending phase (the
     * team modifies the existing code, and the phase is marked done when the run finishes).
     */
    public Map<String, Object> phases(String project, boolean build) {
        if (workspaces == null) {
            return Map.of("error", "Phase-based development is only available under the per-project "
                    + "workspace feature (mcp profile).");
        }
        if (project == null || project.isBlank()) {
            return Map.of("error", "project (a name or path) is required");
        }
        List<java.nio.file.Path> matches = workspaces.resolveExisting(project);
        if (matches.isEmpty()) {
            return Map.of("nextAction", "STOP",
                    "error", "No existing project matches '" + project + "'. Start a phased build with "
                            + "orchestrate_start (phased=true), or pass the full path to its folder.");
        }
        if (matches.size() > 1) {
            List<String> names = matches.stream().map(p -> p.getFileName().toString()).toList();
            return Map.of("needsChoice", true, "candidates", names, "nextAction", "ASK_USER",
                    "message", "Multiple projects match '" + project + "': " + String.join(", ", names)
                            + ". Ask the user which one, then call orchestrate_phases again with the EXACT "
                            + "folder name shown (or the full path).");
        }
        java.nio.file.Path dir = matches.get(0);
        Optional<PhasePlan> plan = phaseStoreFor(dir).load();
        if (plan.isEmpty()) {
            return Map.of("nextAction", "STOP", "project", dir.getFileName().toString(),
                    "message", "No phase plan for '" + dir.getFileName() + "'. It was not started as a "
                            + "phased build. Use orchestrate_start with phased=true for a big project that "
                            + "should be delivered phase by phase.");
        }
        return build ? buildNextPhase(dir, plan.get()) : reportPhases(dir, plan.get());
    }

    /** Cross-session awareness: report the roadmap with each phase's status. Read-only (STOP). */
    private Map<String, Object> reportPhases(java.nio.file.Path dir, PhasePlan plan) {
        List<Map<String, Object>> phaseList = new ArrayList<>();
        for (PhasePlan.Phase p : plan.phases()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("number", p.number());
            entry.put("title", p.title());
            entry.put("description", p.description());
            entry.put("status", p.status().name());
            phaseList.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", dir.getFileName().toString());
        result.put("goal", plan.goal());
        result.put("phases", phaseList);
        result.put("summary", plan.summary());
        result.put("allDone", plan.allDone());
        result.put("nextAction", "STOP");
        result.put("message", plan.allDone()
                ? "All phases are complete. " + plan.summary()
                : plan.summary() + ". Build the next pending phase with orchestrate_phases build=true.");
        return result;
    }

    /** Start a run that builds the next pending phase; the planner advances the plan and scopes it. */
    private Map<String, Object> buildNextPhase(java.nio.file.Path dir, PhasePlan plan) {
        Optional<PhasePlan.Phase> next = plan.nextPending();
        if (next.isEmpty()) {
            return Map.of("nextAction", "STOP", "project", dir.getFileName().toString(),
                    "message", "All phases are already complete — nothing to build. " + plan.summary());
        }
        PhasePlan.Phase phase = next.get();
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("editDir", dir.toString());     // modify the existing code in place
        options.put("rememberProject", true);
        options.put("phaseContinue", true);          // signal: advance the persisted plan
        String request = "Build phase " + phase.number() + ": " + phase.headline();
        String projectId = launch(request, options, false);
        if (projectId == null) {
            return Map.of("error", "timed out starting the phase build");
        }
        return Map.of(
                "projectId", projectId,
                "nextAction", "CALL_NEXT",
                "phase", phase.number(),
                "message", "Building phase " + phase.number() + " of " + plan.phases().size() + " — "
                        + phase.title() + ". The team modifies the existing project at " + dir + "; run "
                        + "the loop AUTONOMOUSLY (orchestrate_next → act → orchestrate_submit) until STOP. "
                        + "The phase is marked done in the roadmap when the run finishes.");
    }

    /** A phase-plan store over a resolved project directory (opens/initialises its Git repo). */
    private PhasePlanStore phaseStoreFor(java.nio.file.Path dir) {
        return new PhasePlanStore(new JGitArtifactRepository(dir));
    }

    /**
     * Submit a project (build or edit) on a virtual thread and block until its first task is ready.
     * Returns the project id, or null if it didn't start in time. Sets it as the active/followed project.
     */
    private String launch(String featureRequest, Map<String, Object> options, boolean retrospective) {
        bridge.armStart();
        retrospectiveEnabled = retrospective && feedbackReporter != null;
        retrospectiveDelivered = false;
        retrospectiveTaskId = null;
        Thread.ofVirtual().name("mcp-project").start(() -> {
            try {
                // tokenBudget empty -> the engine applies the configured ceiling (budgets.yml).
                engine.submit(new OrchestrationEngine.ProjectRequest(featureRequest, options, Optional.empty()));
            } catch (RuntimeException e) {
                System.err.println("[mcp] project submit failed: " + e);
            }
        });
        String projectId;
        try {
            projectId = bridge.awaitProjectId(START_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (projectId == null) {
            return null;
        }
        activeProjectId = projectId;
        activeProject.set(projectId); // let the read-only dashboard follow this project
        return projectId;
    }

    /**
     * Explain an existing, prebuilt project the team has no context of. Parks a single
     * PROJECT_EXPLAINER task (no engine, no build): Claude reads the project at {@code path} and
     * produces an explanation. When {@code rememberProject} is true and a knowledge store is wired,
     * the explanation is also saved as the project brief so future sessions start with context.
     */
    public Map<String, Object> explain(String path, String question, boolean rememberProject) {
        String target = (path == null || path.isBlank()) ? workspaceDir : path.trim();
        String taskId = UUID.randomUUID().toString();
        StringBuilder instructions = new StringBuilder(
                "Explore and explain the existing, prebuilt project located at: ").append(target)
                .append("\nRead its real files (entry points, build/config files, source, tests, docs) "
                        + "with your tools, then explain what it does and how it works. Do NOT modify "
                        + "any files — this is an explanation, not a change.");
        if (question != null && !question.isBlank()) {
            instructions.append("\nFocus especially on: ").append(question.trim());
        }
        McpBridge.PendingTask task = new McpBridge.PendingTask(
                taskId, "explain-" + taskId, AgentRole.PROJECT_EXPLAINER.name(),
                "Explain the project", instructions.toString(),
                AgentPrompts.defaultPrompt(AgentRole.PROJECT_EXPLAINER), instructions.toString(),
                "{\"status\":\"COMPLETED\",\"output\":{\"explanation\":\"Markdown explanation\","
                        + "\"summary\":\"one-paragraph TL;DR\"}}",
                Map.of(), McpBridge.Audience.AGENT);

        explainTasks.put(taskId, rememberProject);
        // Park it; the dispatch blocks until Claude submits — discard the result here, submit() owns it.
        Thread.ofVirtual().name("mcp-explain").start(() -> {
            try {
                bridge.dispatch(task);
            } catch (RuntimeException e) {
                System.err.println("[mcp] explain dispatch failed: " + e);
            }
        });
        return Map.of(
                "nextAction", "CALL_NEXT",
                "message", "Call orchestrate_next to get the PROJECT_EXPLAINER task, act as it (read the "
                        + "project at " + target + " and explain it), then call orchestrate_submit with "
                        + "the explanation. This is a one-off read-only task — after it, present the "
                        + "explanation to the user."
                        + (rememberProject ? " It will also be saved as the project brief." : ""));
    }

    /** Return the next agent task to fulfil, or the project status if none is pending. */
    public Map<String, Object> next() {
        Optional<McpBridge.PendingTask> pending = bridge.poll(POLL_MILLIS);
        if (pending.isPresent()) {
            McpBridge.PendingTask t = pending.get();
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("taskId", t.taskId());
            task.put("role", t.role());
            task.put("title", t.title());
            task.put("description", t.description());
            task.put("persona", t.systemPrompt());
            task.put("instructions", t.instructions());
            task.put("grounding", boundedGrounding(t.grounding()));
            task.put("responseSchema", t.responseSchema());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("task", task);
            response.put("nextAction", "SUBMIT");
            response.put("hint", "Act as this agent now, then immediately call orchestrate_submit "
                    + "with this taskId and your result. Do not ask the user — keep the loop going.");
            return response;
        }
        // Dual-channel human Q&A: with no agent task ready, if the team is waiting on the user,
        // ALSO surface the question here so it can be answered in the CLI chat — the UI shows it in
        // parallel the whole time, and whichever channel answers first wins (the other then finds it
        // already completed). Agent work is polled first above, so this never starves the loop.
        List<McpBridge.PendingTask> openQuestions = bridge.pendingUserQuestions();
        if (!openQuestions.isEmpty()) {
            return userQuery(openQuestions.get(0));
        }
        String state = currentState();
        // End-of-run retrospective: before reporting a terminal state, run one final reflection on
        // friction with the orchestrator (most valuable on FAILED/BLOCKED) and deliver it to you.
        if (retrospectiveEnabled && !retrospectiveDelivered
                && ("DONE".equals(state) || "FAILED".equals(state) || "BLOCKED".equals(state))) {
            return retrospectiveTask(state);
        }
        if ("DONE".equals(state)) {
            markActivePhaseDone(); // advance the phase roadmap (no-op for non-phased projects)
            recordMetricsOnce(state);
            extractLessonsOnce(state);
            releaseActiveFollow(); // the run is over; the dashboard stops following it
            return Map.of("status", state, "nextAction", "STOP",
                    "message", "Project DONE. Loop complete — summarize the result for the user. "
                            + "Generated code is committed in " + projectDir() + "; see RUN.md there for "
                            + "how to run it.");
        }
        if ("FAILED".equals(state) || "BLOCKED".equals(state)) {
            // BLOCKED means work is stuck (e.g. a build that couldn't be fixed left a task in review).
            // Never report this as success — tell the user plainly what is unresolved.
            recordMetricsOnce(state);
            extractLessonsOnce(state);
            releaseActiveFollow();
            return Map.of("status", state, "nextAction", "STOP",
                    "message", "Project " + state + " — it is NOT successfully done. Likely a build/test "
                            + "failure that could not be fixed, or a blocked task. Inspect " + projectDir()
                            + " and the failing tests, report the blocker to the user, and do not present "
                            + "this as a finished product.");
        }
        if ("NEEDS_CLARIFICATION".equals(state)) {
            // A worker is blocked for missing information that wasn't resolved (no answer from the
            // user). Stop rather than polling forever — surface the open question to the user.
            releaseActiveFollow();
            return Map.of("status", state, "nextAction", "STOP",
                    "message", "Project is blocked awaiting clarification that wasn't resolved. Get the "
                            + "missing information from the user, then start a fresh run with it. Do NOT "
                            + "keep calling orchestrate_next — nothing will become ready until it's answered.");
        }
        // Not finished but nothing ready this instant (a task is running): tell the client to retry.
        return Map.of("status", state, "nextAction", "CALL_NEXT",
                "message", "No task ready this moment; call orchestrate_next again to continue the loop.");
    }

    /** Hard ceiling on the total grounding echoed back in a single {@code orchestrate_next} response. */
    private static final int MAX_GROUNDING_CHARS = 24_000;

    /**
     * Defensive cap on the grounding echoed to the driver. Reviewers/QA already get a file LIST (not
     * inlined contents) in MCP mode, but several large upstream hand-offs can still stack up; this
     * guarantees no single task response overflows the tool-result limit (which would force it to a
     * file the driver then can't Read normally). taskId/role/nextAction live in their own small fields,
     * so the driver can always recover them regardless of how big the payload would have been.
     */
    private Map<String, String> boundedGrounding(Map<String, String> grounding) {
        if (grounding == null || grounding.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        int budget = MAX_GROUNDING_CHARS;
        for (Map.Entry<String, String> e : grounding.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            if (budget <= 0) {
                out.put(e.getKey(), "…[omitted to keep this response small; read the project files in "
                        + workspaceDir + " directly]");
                continue;
            }
            if (value.length() > budget) {
                value = value.substring(0, budget)
                        + "\n…[truncated to keep this response small; read the full files in "
                        + workspaceDir + " directly]";
                budget = 0;
            } else {
                budget -= value.length();
            }
            out.put(e.getKey(), value);
        }
        return out;
    }

    /**
     * Surface a pending USER question to the CLI as an ASK_USER prompt. Non-destructive: the same
     * question stays available in the UI ({@code /api/questions}); whichever channel answers first
     * completes it and the other sees it gone.
     */
    private Map<String, Object> userQuery(McpBridge.PendingTask t) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("taskId", t.taskId());
        query.put("forUser", true);
        query.put("title", t.title());
        query.put("questionsForUser", t.description());
        query.put("responseSchema", t.responseSchema());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userQuery", query);
        response.put("nextAction", "ASK_USER");
        response.put("message", "The team is waiting on the USER. This is also open in the web "
                + "dashboard (answerable there by voice or text). If the user replies to you here, "
                + "relay questionsForUser verbatim and submit ONLY their real answer via "
                + "orchestrate_submit with this taskId, output matching responseSchema. If they answer "
                + "in the dashboard instead, your submit will report it already completed — just call "
                + "orchestrate_next again. Do NOT answer on the user's behalf.");
        return response;
    }

    /** Deliver an agent's result for a task and advance the workflow. */
    public Map<String, Object> submit(String taskId, JsonNode result) {
        if (taskId == null || taskId.isBlank()) {
            return Map.of("accepted", false, "error", "taskId is required");
        }
        Agent.Response response = mapper.parse(result);
        if (explainTasks.containsKey(taskId)) {
            return submitExplanation(taskId, response);
        }
        if (taskId.equals(retrospectiveTaskId)) {
            return submitRetrospective(response);
        }
        boolean accepted = bridge.complete(taskId, response);
        if (!accepted) {
            // Most often a benign race: a USER question was already answered in the dashboard. Return
            // a structured signal (not an error) so the loop just continues instead of assuming failure.
            return Map.of("accepted", false, "outcome", "ALREADY_COMPLETED", "nextAction", "CALL_NEXT",
                    "message", "That task was already completed (e.g. the user answered in the "
                            + "dashboard). Nothing to do — call orchestrate_next to continue.");
        }
        String state = currentState();
        boolean finished = "DONE".equals(state) || "FAILED".equals(state);
        if (finished) {
            if ("DONE".equals(state)) {
                markActivePhaseDone(); // advance the phase roadmap (no-op for non-phased projects)
            }
            recordMetricsOnce(state);
            extractLessonsOnce(state);
            releaseActiveFollow(); // the run is over; the dashboard stops following it
        }
        return Map.of(
                "accepted", true,
                "outcome", response.outcome().name(),
                "projectState", state,
                "nextAction", finished ? "STOP" : "CALL_NEXT",
                "message", finished
                        ? "Project " + state + ". Loop complete — the code is in " + projectDir()
                                + ". Summarize for the user."
                        : "Recorded. Immediately call orchestrate_next for the next task — keep looping "
                                + "autonomously until nextAction is STOP.");
    }

    /** Deliver a PROJECT_EXPLAINER result: a one-off explanation, optionally saved as the brief. */
    private Map<String, Object> submitExplanation(String taskId, Agent.Response response) {
        boolean accepted = bridge.complete(taskId, response);
        if (!accepted) {
            return Map.of("accepted", false, "error", "unknown or already-completed taskId: " + taskId);
        }
        boolean remember = Boolean.TRUE.equals(explainTasks.remove(taskId));
        Object explanation = response.structuredOutput().getOrDefault("explanation",
                response.structuredOutput().get("summary"));
        boolean saved = false;
        if (remember && knowledgeStore != null && explanation != null
                && !explanation.toString().isBlank()) {
            knowledgeStore.save(explanation.toString(), taskId, AgentRole.PROJECT_EXPLAINER.name());
            saved = true;
        }
        return Map.of(
                "accepted", true,
                "nextAction", "STOP",
                "savedAsProjectBrief", saved,
                "message", "Explanation complete — present output.explanation to the user."
                        + (saved ? " It was also saved as the project brief for future sessions." : ""));
    }

    /** Synthesize the final RETROSPECTIVE_ANALYST task (not an engine/bridge task). */
    private Map<String, Object> retrospectiveTask(String state) {
        if (retrospectiveTaskId == null) {
            retrospectiveTaskId = "retro-" + UUID.randomUUID();
        }
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", retrospectiveTaskId);
        task.put("role", AgentRole.RETROSPECTIVE_ANALYST.name());
        task.put("title", "Retrospective: how to improve the orchestrator");
        task.put("persona", AgentPrompts.defaultPrompt(AgentRole.RETROSPECTIVE_ANALYST));
        task.put("instructions", "The project just finished with outcome: " + state + ". Run a "
                + "retrospective on THIS run, focused ONLY on friction with the orchestration SYSTEM "
                + "itself (missing roles/capabilities, rigid schemas, weak hand-offs/context, the "
                + "budget, no way to ask the user or run a command, repeated rework/blocked tasks) — "
                + "not bugs in the built project. List concrete improvements to the orchestrator.");
        task.put("responseSchema", "{\"status\":\"COMPLETED\",\"output\":{\"improvements\":"
                + "[{\"problem\":\"...\",\"impact\":\"...\",\"suggestion\":\"...\","
                + "\"severity\":\"HIGH|MEDIUM|LOW\"}],\"summary\":\"...\"}}");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("task", task);
        response.put("nextAction", "SUBMIT");
        response.put("hint", "Final step: reflect on the run you just orchestrated and submit your "
                + "improvement notes for the orchestrator. After submitting, the project is complete.");
        return response;
    }

    /** Deliver the retrospective (email, else backlog file) and end the run. */
    private Map<String, Object> submitRetrospective(Agent.Response response) {
        retrospectiveDelivered = true;
        String report = formatImprovements(response);
        String delivery = feedbackReporter == null || report.isBlank()
                ? "no feedback to send"
                : feedbackReporter.deliver(activeProjectId, currentState(), report);
        return Map.of(
                "accepted", true,
                "nextAction", "STOP",
                "feedbackDelivery", delivery,
                "message", "Retrospective delivered (" + delivery + "). Project complete — summarize "
                        + "the result for the user.");
    }

    /** Render the analyst's improvements into a readable Markdown report for delivery. */
    private static String formatImprovements(Agent.Response r) {
        StringBuilder sb = new StringBuilder();
        Object summary = r.structuredOutput().get("summary");
        if (summary != null && !summary.toString().isBlank()) {
            sb.append(summary).append("\n\n");
        }
        Object improvements = r.structuredOutput().get("improvements");
        if (improvements instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    sb.append("- [").append(field(m, "severity")).append("] ")
                            .append(field(m, "problem")).append('\n');
                    String impact = field(m, "impact");
                    String suggestion = field(m, "suggestion");
                    if (!impact.isBlank()) {
                        sb.append("    impact: ").append(impact).append('\n');
                    }
                    if (!suggestion.isBlank()) {
                        sb.append("    suggestion: ").append(suggestion).append('\n');
                    }
                } else if (item != null && !item.toString().isBlank()) {
                    sb.append("- ").append(item).append('\n');
                }
            }
        }
        return sb.toString().strip();
    }

    private static String field(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString();
    }

    /** Current project status plus the task graph (states + dependencies). */
    public Map<String, Object> status() {
        if (activeProjectId == null) {
            return Map.of("status", "NO_PROJECT", "message", "Call orchestrate_start first.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", activeProjectId);
        result.put("state", currentState());
        try {
            OrchestrationEngine.ProjectStatus s = engine.status(activeProjectId);
            result.put("totalTasks", s.totalTasks());
            result.put("completedTasks", s.completedTasks());
        } catch (RuntimeException e) {
            result.put("totalTasks", 0);
            result.put("completedTasks", 0);
        }
        result.put("graph", graphNodes());
        return result;
    }

    private List<Map<String, Object>> graphNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (activeProjectId == null) {
            return nodes;
        }
        memoryStore.latestCheckpoint(activeProjectId).ifPresent(checkpoint -> {
            GraphSnapshot snapshot = GraphSnapshot.fromBytes(checkpoint.state());
            for (GraphSnapshot.TaskNode node : snapshot.nodes()) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", node.id());
                n.put("title", node.title());
                n.put("role", node.assignedRole() == null ? "" : node.assignedRole());
                n.put("state", node.state());
                n.put("dependsOn", node.dependsOn());
                nodes.add(n);
            }
        });
        return nodes;
    }

    private String currentState() {
        if (activeProjectId == null) {
            return "NO_PROJECT";
        }
        try {
            return engine.status(activeProjectId).state().name();
        } catch (RuntimeException e) {
            return "PLANNING"; // engine has not registered the project until decomposition completes
        }
    }

    String activeProjectId() {
        return activeProjectId;
    }
}
