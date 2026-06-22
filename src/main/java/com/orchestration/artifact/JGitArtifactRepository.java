package com.orchestration.artifact;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Git-backed {@link ArtifactRepository} using JGit (pure Java — no system {@code git} required).
 *
 * <p>Every {@link #write} applies the requested file changes and records them as a single commit,
 * giving free versioning, diffs, rollback and an audit trail, and letting QA check out and run real
 * code. The repository is created on first use if the directory is not already a Git repo. Writes
 * are serialised so commits are atomic with respect to each other.
 */
public class JGitArtifactRepository implements ArtifactRepository {

    private static final String AUTHOR_EMAIL_DOMAIN = "@agents.orchestration.local";

    private final Path root;
    private final Git git;
    private final Object lock = new Object();

    public JGitArtifactRepository(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath();
        try {
            Files.createDirectories(this.root);
            if (Files.isDirectory(this.root.resolve(".git"))) {
                this.git = Git.open(this.root.toFile());
            } else {
                this.git = Git.init().setDirectory(this.root.toFile()).call();
            }
        } catch (IOException | GitAPIException e) {
            throw new ArtifactRepositoryException("Failed to open Git repository at " + this.root, e);
        }
    }

    @Override
    public CommitId write(WriteRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (lock) {
            try {
                for (FileChange change : request.changes()) {
                    Path target = root.resolve(change.path());
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.writeString(target, change.content());
                    git.add().addFilepattern(change.path()).call();
                }
                String author = request.authorAgent() == null ? "agent" : request.authorAgent();
                RevCommit commit = git.commit()
                        .setMessage(commitMessage(request))
                        .setAuthor(author, author + AUTHOR_EMAIL_DOMAIN)
                        .setCommitter(author, author + AUTHOR_EMAIL_DOMAIN)
                        .call();
                return new CommitId(commit.getName());
            } catch (IOException | GitAPIException e) {
                throw new ArtifactRepositoryException("Failed to write artifacts for task " + request.taskId(), e);
            }
        }
    }

    @Override
    public Optional<String> read(String path) {
        Path target = root.resolve(path);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target));
        } catch (IOException e) {
            throw new ArtifactRepositoryException("Failed to read artifact " + path, e);
        }
    }

    @Override
    public List<String> list(String pathPrefix) {
        String prefix = pathPrefix == null ? "" : pathPrefix;
        Set<String> ignored = ignoredPaths();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(p -> p.toString().replace('\\', '/'))
                    .filter(rel -> !rel.startsWith(".git/"))
                    .filter(rel -> !isIgnored(rel, ignored))
                    .filter(rel -> rel.startsWith(prefix))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ArtifactRepositoryException("Failed to list artifacts under " + prefix, e);
        }
    }

    /** Paths Git ignores per the project's {@code .gitignore}(s). Honouring these keeps generated /
     *  scratch output — {@code node_modules/}, a project's {@code backend/.data/} test uploads, build
     *  dirs — out of the file listing, which is the single most-repeated grounding-noise complaint in
     *  the orchestration retros: a static denylist can't know a project's own ignored dirs, but Git
     *  does. Best-effort: any failure yields an empty set so listing never breaks, it just filters less. */
    private Set<String> ignoredPaths() {
        synchronized (lock) {
            try {
                return git.status().call().getIgnoredNotInIndex();
            } catch (GitAPIException | RuntimeException e) {
                return Set.of();
            }
        }
    }

    /** True when {@code rel} is itself ignored or sits under an ignored directory. JGit reports an
     *  ignored directory as a single entry (it does not descend), so a prefix check is required. */
    private static boolean isIgnored(String rel, Set<String> ignored) {
        if (ignored.isEmpty() || rel == null) {
            return false;
        }
        if (ignored.contains(rel)) {
            return true;
        }
        for (String ig : ignored) {
            String dir = ig.endsWith("/") ? ig : ig + "/";
            if (rel.startsWith(dir)) {
                return true;
            }
        }
        return false;
    }

    private String commitMessage(WriteRequest request) {
        String message = request.message() == null || request.message().isBlank()
                ? "Update artifacts"
                : request.message();
        return request.taskId() == null ? message : "[" + request.taskId() + "] " + message;
    }
}
