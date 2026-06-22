package com.orchestration.phase;

import com.orchestration.artifact.ArtifactRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists a project's {@link PhasePlan} as a single Markdown checklist committed in the project
 * repo, so phase progress survives a process restart AND a brand-new session: opening the project
 * later, the team reads exactly what is DONE and what is PENDING instead of re-deriving it.
 *
 * <p>Thin and stateless by design (no memoisation): the plan is read at planning time and written
 * when a phase's status changes — both infrequent — so always reflecting the file on disk is
 * simpler and avoids a stale cache when a different session advanced the plan.
 */
public class PhasePlanStore {

    /** Repository-relative path of the committed phase checklist. */
    public static final String DEFAULT_PATH = ".project/phases.md";

    private final ArtifactRepository repository;
    private final String path;

    public PhasePlanStore(ArtifactRepository repository) {
        this(repository, DEFAULT_PATH);
    }

    public PhasePlanStore(ArtifactRepository repository, String path) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.path = (path == null || path.isBlank()) ? DEFAULT_PATH : path;
    }

    /** The committed phase plan, or empty when this project has none yet (not phase-based). */
    public Optional<PhasePlan> load() {
        return repository.read(path)
                .filter(s -> !s.isBlank())
                .map(PhasePlan::parse)
                .filter(plan -> !plan.phases().isEmpty());
    }

    /** True when a phase plan already exists for this project. */
    public boolean exists() {
        return load().isPresent();
    }

    /** Commit the plan as the checklist file. */
    public void save(PhasePlan plan, String taskId, String authorRole) {
        Objects.requireNonNull(plan, "plan");
        repository.write(new ArtifactRepository.WriteRequest(
                taskId, authorRole, "[PHASE_PLANNER] update phase plan",
                List.of(new ArtifactRepository.FileChange(path, plan.render()))));
    }

    /** Set one phase's status and commit; returns the updated plan (or empty when no plan exists). */
    public Optional<PhasePlan> markStatus(int number, PhasePlan.Status status, String authorRole) {
        return load().map(plan -> {
            PhasePlan updated = plan.withStatus(number, status);
            save(updated, null, authorRole);
            return updated;
        });
    }
}
