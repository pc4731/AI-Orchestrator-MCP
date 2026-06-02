package com.orchestration.knowledge;

import com.orchestration.artifact.ArtifactRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectKnowledgeStoreTest {

    private static final String BRIEF = "# Brain\nDoes X. Built with Y. Decision: Z.";

    @Test
    void savesAndLoadsBackForTheTeam() {
        InMemoryRepo repo = new InMemoryRepo();
        new ProjectKnowledgeStore(repo).save(BRIEF, "task-1", "KNOWLEDGE_CURATOR");

        // The committed file IS the brief (plaintext, diffable, readable).
        assertEquals(BRIEF, repo.files.get(ProjectKnowledgeStore.DEFAULT_PATH));

        // A fresh store (new session) reads it back as prior context.
        assertEquals(Optional.of(BRIEF), new ProjectKnowledgeStore(repo).load());
    }

    @Test
    void loadIsEmptyWhenNoFileExistsYet() {
        assertTrue(new ProjectKnowledgeStore(new InMemoryRepo()).load().isEmpty());
    }

    @Test
    void isInertWhenDisabled() {
        InMemoryRepo repo = new InMemoryRepo();
        ProjectKnowledgeStore store = new ProjectKnowledgeStore(repo, false, ".project/knowledge.md");

        store.save(BRIEF, "task-1", "KNOWLEDGE_CURATOR");

        assertFalse(store.isAvailable());
        assertTrue(store.load().isEmpty(), "disabled -> nothing to read");
        assertTrue(repo.files.isEmpty(), "disabled -> nothing written");
    }

    /** Minimal in-memory ArtifactRepository: keeps the last-written content per path. */
    private static final class InMemoryRepo implements ArtifactRepository {
        final Map<String, String> files = new LinkedHashMap<>();

        @Override public CommitId write(WriteRequest request) {
            for (FileChange c : request.changes()) {
                files.put(c.path(), c.content());
            }
            return new CommitId("sha-" + files.size());
        }

        @Override public Optional<String> read(String path) {
            return Optional.ofNullable(files.get(path));
        }

        @Override public List<String> list(String pathPrefix) {
            List<String> out = new ArrayList<>();
            files.keySet().forEach(p -> { if (p.startsWith(pathPrefix)) out.add(p); });
            return out;
        }
    }
}
