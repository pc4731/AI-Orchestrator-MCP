package com.orchestration.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fluent builder for an {@link LlmClient.Request} (the Builder pattern called out in the design).
 *
 * <p>Centralising prompt assembly here keeps agents free of message-list plumbing and gives one
 * place to attach the stable system prompt (marked cacheable) plus the alternating user/assistant
 * turns.
 */
public final class PromptBuilder {

    private ModelId model;
    private String system;
    private boolean cacheSystemPrompt;
    private Integer maxTokens;
    private Double temperature;
    private final List<LlmClient.Message> messages = new ArrayList<>();
    private final Map<String, Object> options = new LinkedHashMap<>();

    private PromptBuilder() {
    }

    public static PromptBuilder create() {
        return new PromptBuilder();
    }

    public PromptBuilder model(ModelId model) {
        this.model = model;
        return this;
    }

    public PromptBuilder system(String system) {
        this.system = system;
        return this;
    }

    public PromptBuilder cacheSystemPrompt(boolean cache) {
        this.cacheSystemPrompt = cache;
        return this;
    }

    public PromptBuilder maxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    public PromptBuilder temperature(Double temperature) {
        this.temperature = temperature;
        return this;
    }

    public PromptBuilder user(String content) {
        messages.add(new LlmClient.Message(LlmClient.Role.USER, content));
        return this;
    }

    public PromptBuilder assistant(String content) {
        messages.add(new LlmClient.Message(LlmClient.Role.ASSISTANT, content));
        return this;
    }

    public PromptBuilder option(String key, Object value) {
        options.put(key, value);
        return this;
    }

    public LlmClient.Request build() {
        if (model == null) {
            throw new IllegalStateException("model is required");
        }
        if (messages.isEmpty()) {
            throw new IllegalStateException("at least one message is required");
        }
        return new LlmClient.Request(model, system, List.copyOf(messages), maxTokens, temperature,
                cacheSystemPrompt, Map.copyOf(options));
    }

    /** Convenience for the common case: a system prompt plus a single user turn. */
    public static LlmClient.Request of(ModelId model, String system, String user, boolean cacheSystem) {
        return create()
                .model(Objects.requireNonNull(model, "model"))
                .system(system)
                .cacheSystemPrompt(cacheSystem)
                .user(user)
                .build();
    }
}
