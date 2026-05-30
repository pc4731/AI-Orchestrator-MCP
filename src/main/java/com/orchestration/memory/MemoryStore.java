package com.orchestration.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Structured long-term memory and checkpoint store. SQLite-backed in v1 (single file, zero setup),
 * kept behind this interface so the backend can change without touching callers.
 *
 * <p>Two responsibilities, deliberately on one interface because they share a backing store and a
 * project scope:
 * <ol>
 *   <li><b>Structured memory</b> — compact, summarised entries (decisions, scenario→action
 *       mappings, reusable solutions, code patterns, resolved bugs). This is the token-saving
 *       substitute for carrying full transcripts.</li>
 *   <li><b>Checkpoints</b> — opaque serialised snapshots of full orchestration state, written
 *       after every step so a restart or token-budget renewal can resume exactly where it stopped.</li>
 * </ol>
 *
 * <p>Semantic (vector) retrieval is intentionally NOT here — see {@link SemanticMemory}.
 */
public interface MemoryStore {

    // ---- structured memory ----

    void put(MemoryEntry entry);

    Optional<MemoryEntry> get(String key);

    List<MemoryEntry> query(Query query);

    void delete(String key);

    // ---- checkpoints (resumption) ----

    void saveCheckpoint(Checkpoint checkpoint);

    /** The most recent checkpoint for a project; the entry point for resume-from-where-we-stopped. */
    Optional<Checkpoint> latestCheckpoint(String projectId);

    List<Checkpoint> checkpoints(String projectId);

    /** The kinds of structured memory entries the system reuses to save tokens. */
    enum Kind {
        DECISION,
        SCENARIO_ACTION,
        SOLUTION,
        CODE_PATTERN,
        RESOLVED_BUG,
        SUMMARY
    }

    record MemoryEntry(
            String key,
            Kind kind,
            String projectId,
            String content,                 // compact, already-summarised payload
            Map<String, Object> attributes, // structured fields for querying and dedup
            Instant createdAt
    ) {
        public MemoryEntry {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /** A simple structured query. Vector similarity is out of scope here (that is {@link SemanticMemory}). */
    record Query(
            Optional<String> projectId,
            Optional<Kind> kind,
            Map<String, Object> match,
            int limit
    ) {
        public Query {
            match = match == null ? Map.of() : Map.copyOf(match);
        }
    }

    /**
     * An opaque, serialisable snapshot of full orchestration state (task graph, agent states,
     * partial outputs). {@code sequence} is monotonic per project so "latest wins" on resume.
     */
    record Checkpoint(
            String id,
            String projectId,
            long sequence,
            byte[] state,
            Map<String, Object> metadata,
            Instant createdAt
    ) {
        public Checkpoint {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
