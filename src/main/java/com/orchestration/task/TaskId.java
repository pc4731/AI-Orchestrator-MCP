package com.orchestration.task;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque, type-safe identifier for a {@link Task} within a {@link TaskGraph}.
 *
 * <p>Using a value type (rather than a raw {@code String}) prevents accidentally
 * mixing task ids with other identifiers and keeps method signatures self-documenting.
 */
public record TaskId(String value) {

    public TaskId {
        Objects.requireNonNull(value, "TaskId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("TaskId value must not be blank");
        }
    }

    /** Generate a fresh, unique task id. */
    public static TaskId random() {
        return new TaskId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
