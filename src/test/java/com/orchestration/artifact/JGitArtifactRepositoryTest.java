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
