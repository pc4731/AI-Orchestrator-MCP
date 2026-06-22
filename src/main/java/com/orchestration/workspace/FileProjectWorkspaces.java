package com.orchestration.workspace;

import com.orchestration.artifact.JGitArtifactRepository;
import com.orchestration.knowledge.ProjectKnowledgeStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Filesystem-backed {@link ProjectWorkspaces}: each project gets {@code <baseDir>/<slug>}, where the
 * slug is derived from the feature request. A name collision never clobbers an existing folder — it
 * gets a {@code -2}, {@code -3} suffix — so re-running an idea produces a fresh, isolated project
 * rather than mixing into the previous run's repo.
 */
public class FileProjectWorkspaces implements ProjectWorkspaces {

    /** Cap the slug so a long feature request doesn't produce an unwieldy directory name. */
    private static final int MAX_SLUG_CHARS = 40;

    private final Path baseDir;
    private final boolean knowledgeEnabled;
    private final String knowledgePath;

    private final Map<String, Workspace> byProject = new ConcurrentHashMap<>();
    private final Set<Path> usedDirs = new HashSet<>(); // guards against concurrent same-slug opens

    public FileProjectWorkspaces(Path baseDir, boolean knowledgeEnabled, String knowledgePath) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir").toAbsolutePath().normalize();
        this.knowledgeEnabled = knowledgeEnabled;
        this.knowledgePath = (knowledgePath == null || knowledgePath.isBlank())
                ? ProjectKnowledgeStore.DEFAULT_PATH : knowledgePath;
    }

    @Override
    public Workspace open(String projectId, String featureRequest) {
        Objects.requireNonNull(projectId, "projectId");
        Workspace existing = byProject.get(projectId);
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            // Re-check under the lock: two tasks for a new project could race to open it.
            Workspace racewinner = byProject.get(projectId);
            if (racewinner != null) {
                return racewinner;
            }
            Path dir = uniqueDir(slugify(featureRequest));
            Workspace ws = build(dir);
            byProject.put(projectId, ws);
            return ws;
        }
    }

    @Override
    public Workspace get(String projectId) {
        Workspace ws = byProject.get(projectId);
        // Fallback: a caller resolved before submit opened it (shouldn't normally happen). Name it from
        // the id so it is still isolated and predictable.
        return ws != null ? ws : open(projectId, "project-" + shortId(projectId));
    }

    @Override
    public Workspace openExisting(String projectId, Path dir) {
        Objects.requireNonNull(projectId, "projectId");
        Path norm = dir.toAbsolutePath().normalize();
        Workspace existing = byProject.get(projectId);
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            Workspace racewinner = byProject.get(projectId);
            if (racewinner != null) {
                return racewinner;
            }
            usedDirs.add(norm);
            Workspace ws = build(norm); // build opens the existing Git repo (or inits if absent)
            byProject.put(projectId, ws);
            return ws;
        }
    }

    @Override
    public List<Path> resolveExisting(String nameOrPath) {
        if (nameOrPath == null || nameOrPath.isBlank()) {
            return List.of();
        }
        String ref = nameOrPath.trim();
        // A value with a separator (or absolute) is a filesystem path — never fuzzy-matched.
        if (ref.contains("/") || ref.contains(java.io.File.separator) || Path.of(ref).isAbsolute()) {
            Path p = Path.of(ref).toAbsolutePath().normalize();
            return Files.isDirectory(p) ? List.of(p) : List.of();
        }
        // Bare name: an exact folder under the base dir wins outright.
        Path exact = baseDir.resolve(ref);
        if (Files.isDirectory(exact)) {
            return List.of(exact.toAbsolutePath().normalize());
        }
        // Otherwise return every folder whose name starts with the reference (caller disambiguates).
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> entries = Files.list(baseDir)) {
            entries.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().startsWith(ref))
                    .sorted()
                    .forEach(d -> matches.add(d.toAbsolutePath().normalize()));
        } catch (IOException e) {
            return List.of();
        }
        return matches;
    }

    @Override
    public Optional<String> directoryOf(String projectId) {
        Workspace ws = byProject.get(projectId);
        return ws == null ? Optional.empty() : Optional.of(ws.dir());
    }

    @Override
    public Optional<Path> existingForName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        // A fresh build names its folder slugify(name); if that exact folder already exists, naming
        // would slide to a -2 copy. Report the collision so the caller can offer edit-in-place instead.
        Path dir = baseDir.resolve(slugify(name));
        return Files.isDirectory(dir) ? Optional.of(dir.toAbsolutePath().normalize()) : Optional.empty();
    }

    private Workspace build(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to create project workspace at " + dir, e);
        }
        JGitArtifactRepository repo = new JGitArtifactRepository(dir);
        ProjectKnowledgeStore knowledge = new ProjectKnowledgeStore(repo, knowledgeEnabled, knowledgePath);
        return new Workspace(dir.toString(), repo, knowledge);
    }

    /** First free {@code <baseDir>/<slug>}, appending -2, -3… so an existing folder is never reused. */
    private Path uniqueDir(String slug) {
        Path candidate = baseDir.resolve(slug);
        int n = 2;
        while (Files.exists(candidate) || usedDirs.contains(candidate)) {
            candidate = baseDir.resolve(slug + "-" + n++);
        }
        usedDirs.add(candidate);
        return candidate;
    }

    /** A readable, filesystem-safe folder name from the feature request. */
    static String slugify(String featureRequest) {
        String s = featureRequest == null ? "" : featureRequest.toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.length() > MAX_SLUG_CHARS) {
            s = s.substring(0, MAX_SLUG_CHARS).replaceAll("-+$", "");
        }
        return s.isBlank() ? "project" : s;
    }

    private static String shortId(String projectId) {
        return projectId.length() <= 8 ? projectId : projectId.substring(0, 8);
    }
}
