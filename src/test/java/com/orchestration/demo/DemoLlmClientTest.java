package com.orchestration.demo;

import com.orchestration.llm.LlmClient;
import com.orchestration.llm.ModelId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoLlmClientTest {

    private final DemoLlmClient client = new DemoLlmClient();

    private static LlmClient.Request prompt(String system, String user) {
        return new LlmClient.Request(new ModelId("m"), system,
                List.of(new LlmClient.Message(LlmClient.Role.USER, user)), 1024, 0.2, false, Map.of());
    }

    @Test
    void returnsDecompositionForTeamLeadPrompt() {
        String content = client.complete(prompt("You are the Team Lead", "Please decompose this request")).content();
        assertTrue(content.contains("\"tasks\""));
    }

    @Test
    void returnsCodeArtifactForDeveloperPrompt() {
        String content = client.complete(
                prompt("developer", "As the developer, implement the required code.")).content();
        assertTrue(content.contains("artifacts"));
        assertTrue(content.contains("Calculator.java"));
    }

    @Test
    void returnsDesignOtherwise() {
        String content = client.complete(prompt("You are the Backend Architect", "Design it")).content();
        assertTrue(content.contains("architecture"));
    }
}
