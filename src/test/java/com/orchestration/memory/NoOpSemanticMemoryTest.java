package com.orchestration.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NoOpSemanticMemoryTest {

    @Test
    void indexIsNoOpAndSearchReturnsEmpty() {
        NoOpSemanticMemory memory = new NoOpSemanticMemory();
        memory.index(new SemanticMemory.Document("d1", "some content", Map.of("k", "v")));
        assertTrue(memory.search("anything", 5).isEmpty());
    }
}
