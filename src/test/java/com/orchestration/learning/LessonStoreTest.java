package com.orchestration.learning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LessonStoreTest {

    private static Lesson pending(String id, String role, String evidence) {
        return new Lesson(id, "p1", role, Lesson.BUILD_FIX, "lesson text", evidence, 1,
                Lesson.PENDING, "2026-06-05T10:00:00Z");
    }

    @Test
    void recordsAndListsPending(@TempDir Path dir) {
        LessonStore store = new LessonStore(dir.resolve("proposals.jsonl"));
        store.record(pending("a", "BACKEND_DEVELOPER", "npe in Calc"));
        store.record(pending("b", "QA_ENGINEER", "timeout"));

        assertEquals(2, store.pending().size());
    }

    @Test
    void dedupsBySignatureAndBumpsRecurrence(@TempDir Path dir) {
        LessonStore store = new LessonStore(dir.resolve("proposals.jsonl"));
        store.record(pending("a", "BACKEND_DEVELOPER", "the same failure"));
        store.record(pending("b", "BACKEND_DEVELOPER", "the same failure")); // same signature

        List<Lesson> all = store.all();
        assertEquals(1, all.size(), "a recurring lesson is deduped, not duplicated");
        assertEquals(2, all.get(0).recurrence(), "recurrence is bumped instead");
    }

    @Test
    void decideMovesItOutOfPending(@TempDir Path dir) {
        LessonStore store = new LessonStore(dir.resolve("proposals.jsonl"));
        store.record(pending("a", "BACKEND_DEVELOPER", "x"));

        assertTrue(store.decide("a", Lesson.APPROVED));
        assertTrue(store.pending().isEmpty());
        assertEquals(Lesson.APPROVED, store.get("a").orElseThrow().status());
        assertFalse(store.decide("missing", Lesson.REJECTED));
    }

    @Test
    void survivesReload(@TempDir Path dir) {
        Path file = dir.resolve("proposals.jsonl");
        new LessonStore(file).record(pending("a", "BACKEND_DEVELOPER", "x"));

        assertEquals(1, new LessonStore(file).pending().size(), "proposals persist across instances");
    }
}
