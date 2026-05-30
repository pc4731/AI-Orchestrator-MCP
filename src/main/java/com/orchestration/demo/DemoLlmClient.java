package com.orchestration.demo;

import com.orchestration.llm.LlmClient;
import com.orchestration.llm.TokenUsage;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An offline {@link LlmClient} used only by the {@code demo} profile, so the end-to-end demo runs
 * without an API key or network. It returns role-appropriate canned JSON by inspecting the prompt
 * (Team Lead decomposition / developer code / architect design), which keeps the demo deterministic
 * regardless of task scheduling order.
 */
public class DemoLlmClient implements LlmClient {

    private static final String DECOMPOSITION = """
            {"status":"COMPLETED","confidence":"HIGH",
             "assumptions":["REST/JSON API","in-memory state is acceptable for v1"],
             "output":{"tasks":[
               {"id":"arch","title":"Design calculator service architecture",
                "description":"Design a small service exposing add and subtract","role":"BACKEND_ARCHITECT","dependsOn":[]},
               {"id":"impl","title":"Implement calculator endpoints",
                "description":"Implement add and subtract per the architecture","role":"BACKEND_DEVELOPER","dependsOn":["arch"]},
               {"id":"qa","title":"Test the calculator service",
                "description":"Run the project test suite","role":"QA_ENGINEER","dependsOn":["impl"]}
             ]}}""";

    private static final String DESIGN = """
            {"status":"COMPLETED","confidence":"HIGH",
             "assumptions":["Spring Boot REST controller"],
             "output":{"architecture":"A CalculatorController exposing /add and /subtract delegating to a CalculatorService; no persistence required for v1."}}""";

    private static final String CODE = """
            {"status":"COMPLETED","confidence":"HIGH",
             "output":{"summary":"Implemented add and subtract in a small calculator class."},
             "artifacts":[
               {"path":"src/main/java/demo/Calculator.java",
                "content":"package demo;\\n\\npublic class Calculator {\\n    public int add(int a, int b) { return a + b; }\\n    public int subtract(int a, int b) { return a - b; }\\n}\\n"}
             ]}""";

    @Override
    public Response complete(Request request) {
        // A small, deliberate pause so the live UI visibly steps through each agent's turn.
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String prompt = (request.system() == null ? "" : request.system()) + "\n"
                + request.messages().stream().map(Message::content).collect(Collectors.joining("\n"));
        String lower = prompt.toLowerCase();

        String content;
        if (lower.contains("decompose")) {
            content = DECOMPOSITION;
        } else if (lower.contains("implement the required code")) {
            content = CODE;
        } else {
            content = DESIGN;
        }
        return new Response(content, request.model(), StopReason.END_TURN,
                new TokenUsage(1200, 800, 0, 0), Optional.of("demo"));
    }

    @Override
    public Response stream(Request request, StreamHandler handler) {
        Response response = complete(request);
        handler.onComplete(response);
        return response;
    }
}
