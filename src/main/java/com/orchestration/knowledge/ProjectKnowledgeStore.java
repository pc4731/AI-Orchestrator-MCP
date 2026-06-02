package com.orchestration.knowledge;

import com.orchestration.artifact.ArtifactRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The team's portable "project brain": a single Markdown file, committed alongside the generated
 * code, that captures what the project does and how — so a fresh session can read it instead of
 * re-deriving understanding from the whole codebase (saving tokens and time on edits).
 *
 * <p>Plaintext on purpose: the brief summarises source code that ships in the same repo, so there is
 * nothing to hide from anyone who already has the code; keeping it readable makes it diffable,
 * reviewable, and free of key-management friction. (If a project ever needs to record genuine
 * secrets that are <i>not</i> in the code, that's a separate, encrypted store — not this.)
 *
 * <p>When {@code enabled} is false the store is inert: {@link #load()} returns empty and {@link #save}
 * is a no-op, so the team simply works from the code as before. The plaintext is memoised so the
 * team reads once per session, not per task; {@link #save} refreshes that cache.
 */
public class ProjectKnowledgeStore {

    /** Repository-relative path of the committed knowledge file. */
    public static final String DEFAULT_PATH = ".project/knowledge.md";

    private final ArtifactRepository repository;
    private final boolean enabled;
    private final String path;

    private volatile String cached; // memoised brief for this session
    private volatile boolean loaded;

    public ProjectKnowledgeStore(ArtifactRepository repository) {
        this(repository, true, DEFAULT_PATH);
    }

    public ProjectKnowledgeStore(ArtifactRepository repository, boolean enabled, String path) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.enabled = enabled;
        this.path = (path == null || path.isBlank()) ? DEFAULT_PATH : path;
    }

    /** True when the feature is on, i.e. the team reads/writes the knowledge file. */
    public boolean isAvailable() {
        return enabled;
    }

    /** The project brief, or empty when the feature is off or no file exists yet (greenfield). */
    public Optional<String> load() {
        if (!enabled) {
            return Optional.empty();
        }
        if (loaded) {
            return Optional.ofNullable(cached);
        }
        synchronized (this) {
            if (!loaded) {
                cached = repository.read(path).filter(s -> !s.isBlank()).orElse(null);
                loaded = true;
            }
        }
        return Optional.ofNullable(cached);
    }

    /** Commit the curated brief as the knowledge file. No-op when the feature is off. */
    public void save(String brief, String taskId, String authorRole) {
        if (!enabled || brief == null || brief.isBlank()) {
            return;
        }
        repository.write(new ArtifactRepository.WriteRequest(
                taskId, authorRole, "[KNOWLEDGE_CURATOR] update project knowledge brief",
                List.of(new ArtifactRepository.FileChange(path, brief))));
        cached = brief;
        loaded = true;
    }
}
