package com.orchestration.phase;

import com.orchestration.artifact.JGitArtifactRepository;
import com.orchestration.phase.PhasePlan.Phase;
import com.orchestration.phase.PhasePlan.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhasePlanStoreTest {

    @TempDir
    Path dir;

    private PhasePlan plan() {
        return new PhasePlan("a goal", List.of(
                new Phase(1, "Foundation", "core", Status.IN_PROGRESS),
                new Phase(2, "Next", "more", Status.PENDING)));
    }

    @Test
    void savesAndLoadsAcrossFreshStoreInstances() {
        JGitArtifactRepository repo = new JGitArtifactRepository(dir);
        new PhasePlanStore(repo).save(plan(), "t1", "PHASE_PLANNER");

        // A brand-new store over a freshly-opened repo = the "new session" case: it must read the
        // committed plan from disk, not from any in-memory state.
        Optional<PhasePlan> loaded = new PhasePlanStore(new JGitArtifactRepository(dir)).load();

        assertTrue(loaded.isPresent());
        assertEquals("a goal", loaded.get().goal());
        assertEquals(2, loaded.get().phases().size());
        assertEquals(Status.IN_PROGRESS, loaded.get().phases().get(0).status());
    }

    @Test
    void loadIsEmptyWhenNoPlanCommitted() {
        assertFalse(new PhasePlanStore(new JGitArtifactRepository(dir)).load().isPresent());
        assertFalse(new PhasePlanStore(new JGitArtifactRepository(dir)).exists());
    }

    @Test
    void markStatusAdvancesAndPersists() {
        JGitArtifactRepository repo = new JGitArtifactRepository(dir);
        PhasePlanStore store = new PhasePlanStore(repo);
        store.save(plan(), "t1", "PHASE_PLANNER");

        store.markStatus(1, Status.DONE, "PHASE_PLANNER");

        PhasePlan reloaded = new PhasePlanStore(new JGitArtifactRepository(dir)).load().orElseThrow();
        assertEquals(Status.DONE, reloaded.phases().get(0).status());
        assertEquals(2, reloaded.nextPending().orElseThrow().number());
    }
}
