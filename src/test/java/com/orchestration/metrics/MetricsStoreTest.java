package com.orchestration.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsStoreTest {

    private static RunMetrics run(String id, int rework) {
        return new RunMetrics(id, "DONE", "2026-06-04T10:00:00Z", 1000L, 5, 5, 0,
                rework, 0, 1, 0, 0, Map.of("BACKEND_DEVELOPER", new RoleMetrics(2, 1, 1, 0, 0, rework)));
    }

    @Test
    void appendsAndReadsBackRunsOldestFirst(@TempDir Path dir) {
        MetricsStore store = new MetricsStore(dir.resolve("runs.jsonl"));
        store.record(run("p1", 3));
        store.record(run("p2", 1));

        List<RunMetrics> recent = store.recent(10);

        assertEquals(2, recent.size());
        assertEquals("p1", recent.get(0).projectId());
        assertEquals("p2", recent.get(1).projectId());
        assertEquals(1, recent.get(1).reworkDispatches());
        assertEquals(1, recent.get(0).byRole().get("BACKEND_DEVELOPER").completed());
    }

    @Test
    void recentTrimsToTheLastN(@TempDir Path dir) {
        MetricsStore store = new MetricsStore(dir.resolve("runs.jsonl"));
        store.record(run("p1", 5));
        store.record(run("p2", 3));
        store.record(run("p3", 1));

        List<RunMetrics> recent = store.recent(2);

        assertEquals(2, recent.size());
        assertEquals("p2", recent.get(0).projectId());
        assertEquals("p3", recent.get(1).projectId());
    }

    @Test
    void readingAMissingFileIsEmpty(@TempDir Path dir) {
        assertTrue(new MetricsStore(dir.resolve("none.jsonl")).recent(10).isEmpty());
    }
}
