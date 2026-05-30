package com.orchestration.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    @Test
    void buildsRequestWithSystemAndMessageTurns() {
        LlmClient.Request request = PromptBuilder.create()
                .model(new ModelId("m"))
                .system("sys")
                .cacheSystemPrompt(true)
                .maxTokens(100)
                .temperature(0.3)
                .user("hello")
                .assistant("hi")
                .user("more")
                .build();

        assertEquals("m", request.model().value());
        assertEquals("sys", request.system());
        assertTrue(request.cacheSystemPrompt());
        assertEquals(100, request.maxTokens());
        assertEquals(3, request.messages().size());
        assertEquals(LlmClient.Role.USER, request.messages().get(0).role());
        assertEquals(LlmClient.Role.ASSISTANT, request.messages().get(1).role());
    }

    @Test
    void requiresModel() {
        assertThrows(IllegalStateException.class, () -> PromptBuilder.create().user("x").build());
    }

    @Test
    void requiresAtLeastOneMessage() {
        assertThrows(IllegalStateException.class, () -> PromptBuilder.create().model(new ModelId("m")).build());
    }

    @Test
    void convenienceFactoryBuildsSingleUserTurn() {
        LlmClient.Request request = PromptBuilder.of(new ModelId("m"), "sys", "question", true);
        assertEquals(1, request.messages().size());
        assertEquals("question", request.messages().get(0).content());
        assertTrue(request.cacheSystemPrompt());
    }
}
