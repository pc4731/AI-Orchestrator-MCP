package com.orchestration.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteMemoryStoreTest {

    private SqliteMemoryStore store;

    @BeforeEach
    void setUp() {
        store = SqliteMemoryStore.inMemory();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private static MemoryStore.MemoryEntry entry(String key, MemoryStore.Kind kind, String projectId,
                                                 String content, Map<String, Object> attrs) {
        return new MemoryStore.MemoryEntry(key, kind, projectId, content, attrs, Instant.now());
    }

    @Test
    void putAndGetRoundTrip() {
        store.put(entry("k1", MemoryStore.Kind.DECISION, "p1", "use sqlite", Map.of("topic", "memory")));

        MemoryStore.MemoryEntry got = store.get("k1").orElseThrow();
        assertEquals("use sqlite", got.content());
        assertEquals(MemoryStore.Kind.DECISION, got.kind());
        assertEquals("p1", got.projectId());
        assertEquals("memory", got.attributes().get("topic"));
    }

    @Test
    void queryFiltersByProjectKindAndAttributeMatch() {
        store.put(entry("a", MemoryStore.Kind.DECISION, "p1", "x", Map.of("tag", "keep")));
        store.put(entry("b", MemoryStore.Kind.SOLUTION, "p1", "y", Map.of("tag", "keep")));
        store.put(entry("c", MemoryStore.Kind.DECISION, "p2", "z", Map.of("tag", "keep")));
        store.put(entry("d", MemoryStore.Kind.DECISION, "p1", "w", Map.of("tag", "skip")));

        var results = store.query(new MemoryStore.Query(
                Optional.of("p1"), Optional.of(MemoryStore.Kind.DECISION), Map.of("tag", "keep"), 0));

        assertEquals(1, results.size());
        assertEquals("a", results.get(0).key());
    }

    @Test
    void queryRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            store.put(entry("k" + i, MemoryStore.Kind.SUMMARY, "p", "c", Map.of()));
        }
        var results = store.query(new MemoryStore.Query(Optional.of("p"), Optional.empty(), Map.of(), 3));
        assertEquals(3, results.size());
    }

    @Test
    void deleteRemovesEntry() {
        store.put(entry("k", MemoryStore.Kind.DECISION, "p", "c", Map.of()));
        store.delete("k");
        assertTrue(store.get("k").isEmpty());
    }

    @Test
    void checkpointsPersistAndLatestWins() {
        store.saveCheckpoint(new MemoryStore.Checkpoint(
                "c1", "p", 1, new byte[]{1, 2}, Map.of("projectState", "IN_PROGRESS"), Instant.now()));
        store.saveCheckpoint(new MemoryStore.Checkpoint(
                "c2", "p", 2, new byte[]{3, 4}, Map.of("projectState", "DONE"), Instant.now()));

        MemoryStore.Checkpoint latest = store.latestCheckpoint("p").orElseThrow();
        assertEquals(2, latest.sequence());
        assertEquals("DONE", latest.metadata().get("projectState"));
        assertArrayEquals(new byte[]{3, 4}, latest.state());
        assertEquals(2, store.checkpoints("p").size());
    }

    @Test
    void latestCheckpointEmptyForUnknownProject() {
        assertTrue(store.latestCheckpoint("missing").isEmpty());
    }
}
