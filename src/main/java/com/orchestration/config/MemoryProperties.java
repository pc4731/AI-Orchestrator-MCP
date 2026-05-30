package com.orchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code config/memory.yml}: structured-memory backend, checkpoint policy, semantic toggle. */
@ConfigurationProperties("memory")
public record MemoryProperties(String backend, Sqlite sqlite, Checkpoint checkpoint, Semantic semantic) {

    public record Sqlite(String path) {
    }

    public record Checkpoint(boolean everyStep) {
    }

    public record Semantic(boolean enabled, String backend) {
    }
}
