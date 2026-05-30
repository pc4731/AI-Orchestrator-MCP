package com.orchestration.llm;

import java.util.Objects;

/**
 * Identifier for a Claude model, e.g. {@code "claude-opus-4-8"} or {@code "claude-sonnet-4-6"}.
 *
 * <p>A value type so model selection is explicit and type-safe. Per the README, the actual id
 * for each agent comes from YAML configuration and is never hardcoded in agent logic.
 */
public record ModelId(String value) {

    public ModelId {
        Objects.requireNonNull(value, "ModelId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ModelId value must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
