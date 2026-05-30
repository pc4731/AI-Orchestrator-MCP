package com.orchestration.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Abstraction over the Anthropic Claude API.
 *
 * <p>Hides retries with exponential backoff, rate-limit handling, streaming, token accounting and
 * prompt caching from the agents, which simply ask for a completion. Per-agent model selection is
 * a parameter ({@link Request#model()}), chosen from YAML config — this is the <b>Strategy</b>
 * pattern: the same agent code runs against whichever model the configuration assigns.
 *
 * <p>Callers run on virtual threads, so a blocking {@link #complete} call is the simple default;
 * {@link #stream} is offered where incremental output matters.
 */
public interface LlmClient {

    /** Blocking completion. */
    Response complete(Request request);

    /** Streaming completion; tokens are delivered to {@code handler} as they arrive. The fully
     *  assembled {@link Response} is also returned for convenience. */
    Response stream(Request request, StreamHandler handler);

    record Request(
            ModelId model,
            String system,              // stable system prompt; mark cacheable via cacheSystemPrompt
            List<Message> messages,
            Integer maxTokens,
            Double temperature,
            boolean cacheSystemPrompt,  // enable Anthropic prompt caching for the system block
            Map<String, Object> options
    ) {
        public Request {
            messages = messages == null ? List.of() : List.copyOf(messages);
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    record Message(Role role, String content) {}

    enum Role { USER, ASSISTANT }

    record Response(
            String content,
            ModelId model,
            StopReason stopReason,
            TokenUsage usage,
            Optional<String> requestId
    ) {}

    enum StopReason { END_TURN, MAX_TOKENS, STOP_SEQUENCE, TOOL_USE, REFUSAL, ERROR }

    /** Callback for streamed output. {@code onComplete}/{@code onError} are optional. */
    interface StreamHandler {
        void onToken(String token);

        default void onComplete(Response response) {
        }

        default void onError(Throwable error) {
        }
    }
}
