package com.orchestration.artifact;

import com.orchestration.artifact.ArtifactRepository.FileChange;
import com.orchestration.artifact.ArtifactRepository.WriteRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JGitArtifactRepositoryTest {

    @TempDir
    Path dir;

    @Test
    void writeCommitsAndReadsBack() {
        JGitArtifactRepository repo = new JGitArtifactRepository(dir);

        ArtifactRepository.CommitId id = repo.write(new WriteRequest("t1", "backend-dev", "add file",
                List.of(new FileChange("src/Main.java", "class Main {}"))));

        assertNotNull(id.sha());
        assertEquals(40, id.sha().length());
        assertEquals("class Main {}", repo.read("src/Main.java").orElseThrow());
    }

    @Test
    void listReturnsCommittedFilesExcludingGitInternals() {
        JGitArtifactRepository repo = new JGitArtifactRepository(dir);
        repo.write(new WriteRequest("t1", "a", "m",
                List.of(new FileChange("a.txt", "1"), new FileChange("sub/b.txt", "2"))));

        List<String> files = repo.list("");
        assertTrue(files.contains("a.txt"));
        assertTrue(files.contains("sub/b.txt"));
        assertFalse(files.stream().anyMatch(f -> f.startsWith(".git/")));
    }

    @Test
    void listFiltersByPrefix() {
        JGitArtifactRepository repo = new JGitArtifactRepository(dir);
        repo.write(new WriteRequest("t1", "a", "m",
                List.of(new FileChange("src/A.java", "1"), new FileChange("docs/x.md", "2"))));

        assertEquals(List.of("src/A.java"), repo.list("src/"));
    }

    @Test
    void listExcludesGitignoredPaths() throws java.io.IOException {
        JGitArtifactRepository repo = new JGitArtifactRepository(dir);
        // A project's own .gitignore — the recurring offender is a per-project scratch dir like
        // backend/.data that no static denylist could anticipate.
        repo.write(new WriteRequest("t1", "a", "m", List.of(
                new FileChange(".gitignore", "backend/.data/\nnode_modules/\n"),
                new FileChange("backend/app.js", "code"))));
        // Generated/scratch files that are NOT committed but sit on disk under ignored dirs.
        java.nio.file.Files.createDirectories(dir.resolve("backend/.data/uploads/x"));
        java.nio.file.Files.writeString(dir.resolve("backend/.data/app.db"), "sqlite");
        java.nio.file.Files.writeString(dir.resolve("backend/.data/uploads/x/source.mp3"), "bytes");
        java.nio.file.Files.createDirectories(dir.resolve("node_modules/dep"));
        java.nio.file.Files.writeString(dir.resolve("node_modules/dep/index.js"), "vendored");

        List<String> files = repo.list("");

        assertTrue(files.contains("backend/app.js"));
        assertTrue(files.contains(".gitignore"));
        assertFalse(files.stream().anyMatch(f -> f.startsWith("backend/.data/")),
                "gitignored scratch dir must not appear in the listing");
        assertFalse(files.stream().anyMatch(f -> f.startsWith("node_modules/")),
                "gitignored dependency dir must not appear in the listing");
    }

    @Test
    void reopensExistingRepository() {
        new JGitArtifactRepository(dir).write(new WriteRequest("t", "a", "m",
                List.of(new FileChange("x.txt", "hi"))));

        JGitArtifactRepository reopened = new JGitArtifactRepository(dir);
        assertEquals("hi", reopened.read("x.txt").orElseThrow());
    }

    @Test
    void readMissingFileReturnsEmpty() {
        assertTrue(new JGitArtifactRepository(dir).read("nope.txt").isEmpty());
    }
}
