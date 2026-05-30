package com.orchestration.agent;

import java.util.Objects;
import java.util.UUID;

/** Type-safe identifier for a concrete {@link Agent} instance. */
public record AgentId(String value) {

    public AgentId {
        Objects.requireNonNull(value, "AgentId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("AgentId value must not be blank");
        }
    }

    public static AgentId random() {
        return new AgentId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
