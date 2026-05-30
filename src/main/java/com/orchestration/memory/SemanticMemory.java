package com.orchestration.memory;

import java.util.List;
import java.util.Map;

/**
 * Semantic (vector) retrieval — the RAG extension point, <b>DEFERRED in v1</b>.
 *
 * <p>Per the README, the vector layer is not built for v1; structured memory plus summarisation
 * carries the token savings. A no-op implementation is wired by default so the rest of the system
 * can depend on this seam today, and PGVector (or similar) can be plugged in later without any
 * engine change.
 *
 * <p>Kept deliberately minimal so nothing in v1 grows a hard dependency on vector search.
 */
public interface SemanticMemory {

    /** Index a document for later similarity search. A no-op in the v1 default implementation. */
    void index(Document document);

    /** Return up to {@code topK} semantically similar documents. Empty in the v1 default. */
    List<Hit> search(String query, int topK);

    record Document(String id, String content, Map<String, Object> metadata) {
        public Document {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record Hit(Document document, double score) {}
}
