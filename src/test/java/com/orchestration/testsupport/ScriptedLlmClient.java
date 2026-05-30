package com.orchestration.testsupport;

import com.orchestration.llm.LlmClient;
import com.orchestration.llm.ModelId;
import com.orchestration.llm.TokenUsage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Test double for {@link LlmClient} that returns pre-scripted response bodies in order and records
 * every request it receives. Lets agent/engine tests run the full LLM flow with no network or key.
 */
public class ScriptedLlmClient implements LlmClient {

    private final Deque<String> contents;
    public final List<Request> captured = new ArrayList<>();

    public ScriptedLlmClient(String... contents) {
        this.contents = new ArrayDeque<>(Arrays.asList(contents));
    }

    @Override
    public Response complete(Request request) {
        captured.add(request);
        String content = contents.isEmpty() ? "{}" : contents.poll();
        return new Response(content, new ModelId("test-model"), StopReason.END_TURN,
                new TokenUsage(1, 1, 0, 0), Optional.empty());
    }

    @Override
    public Response stream(Request request, StreamHandler handler) {
        Response response = complete(request);
        handler.onComplete(response);
        return response;
    }

    public String lastUserPrompt() {
        Request last = captured.get(captured.size() - 1);
        return last.messages().get(last.messages().size() - 1).content();
    }
}
