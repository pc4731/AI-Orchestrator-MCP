package com.orchestration.memory;

import java.util.List;

/**
 * The default {@link SemanticMemory} for v1: semantic/vector RAG is deferred, so indexing is a
 * no-op and searches return nothing. Wiring this in (rather than leaving the dependency null) lets
 * the rest of the system call the seam unconditionally; a real PGVector-backed implementation can
 * replace it later without any other change.
 */
public class NoOpSemanticMemory implements SemanticMemory {

    @Override
    public void index(Document document) {
        // intentionally no-op (RAG deferred to a later version)
    }

    @Override
    public List<Hit> search(String query, int topK) {
        return List.of();
    }
}
