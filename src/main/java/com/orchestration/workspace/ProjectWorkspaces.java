package com.orchestration.workspace;

import com.orchestration.artifact.ArtifactRepository;
import com.orchestration.knowledge.ProjectKnowledgeStore;

/**
 * Gives every project its own isolated workspace — a dedicated directory, Git-backed
 * {@link ArtifactRepository}, and {@link ProjectKnowledgeStore} — instead of all projects sharing one
 * working tree. Without this, a second run sees the first run's files and their Git histories mix.
 *
 * <p>The workspace is created once, at project submit, named from the feature request so the output
 * lands somewhere a human can find (e.g. {@code ~/Desktop/calculator-service}). Downstream callers
 * (the task processor, the planner) resolve the same workspace by project id via {@link #get}.
 */
public interface ProjectWorkspaces {

    /** A project's isolated working area: where its code lives and the repo/knowledge backed by it. */
    record Workspace(String dir, ArtifactRepository repository, ProjectKnowledgeStore knowledge) {}

    /**
     * Create (or return, if already open) the workspace for a project, naming the directory from the
     * feature request on first call. Idempotent per project id.
     */
    Workspace open(String projectId, String featureRequest);

    /** The already-open workspace for a project, lazily created with a fallback name if {@link #open}
     *  was never called for it. */
    Workspace get(String projectId);

    /** Open an EXISTING directory as a project's workspace (for edits) — reuses its files and Git
     *  history rather than creating a fresh slug folder. Idempotent per project id. */
    Workspace openExisting(String projectId, java.nio.file.Path dir);

    /**
     * Resolve a project reference to existing directories. A value containing a path separator is
     * treated as a filesystem path (0 or 1 match); a bare name is looked up under the base dir — an
     * exact folder name wins outright, otherwise every folder whose name starts with it is returned
     * (so the caller can ask the user to disambiguate when more than one matches).
     */
    java.util.List<java.nio.file.Path> resolveExisting(String nameOrPath);

    /** The directory of an already-open workspace, or empty if none was opened — never creates one. */
    java.util.Optional<String> directoryOf(String projectId);

    /**
     * The existing folder a fresh build named {@code name} would collide with (same slug already on
     * disk), or empty if the name is free. Lets a caller offer edit-in-place instead of silently
     * creating a {@code -2} copy. Default: no collision (for implementations without on-disk folders).
     */
    default java.util.Optional<java.nio.file.Path> existingForName(String name) {
        return java.util.Optional.empty();
    }
}
