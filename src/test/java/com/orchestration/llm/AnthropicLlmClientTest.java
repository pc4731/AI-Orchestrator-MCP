package com.orchestration.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicLlmClientTest {

    private static final String OK_JSON = """
            {"id":"msg_1","model":"m","stop_reason":"end_turn",
             "content":[{"type":"text","text":"Hello!"}],
             "usage":{"input_tokens":10,"output_tokens":3,"cache_read_input_tokens":2,"cache_creation_input_tokens":1}}""";

    private static AnthropicClientConfig config(String apiKey) {
        return new AnthropicClientConfig("http://api.test", "2023-06-01", apiKey,
                Duration.ofSeconds(5), 3, 1, 5, 2.0, true);
    }

    private static LlmClient.Request request() {
        return new LlmClient.Request(new ModelId("m"), "sys",
                List.of(new LlmClient.Message(LlmClient.Role.USER, "hi")), 100, 0.2, false, Map.of());
    }

    private static StreamingTransport noStreaming() {
        return (url, headers, body) -> Stream.of();
    }

    @Test
    void completeParsesContentStopReasonAndUsage() {
        HttpTransport ok = (url, headers, body) -> new HttpResult(200, OK_JSON, Map.of());
        AnthropicLlmClient client = new AnthropicLlmClient(config("key"), ok, noStreaming());

        LlmClient.Response response = client.complete(request());

        assertEquals("Hello!", response.content());
        assertEquals(LlmClient.StopReason.END_TURN, response.stopReason());
        assertEquals(10, response.usage().inputTokens());
        assertEquals(3, response.usage().outputTokens());
        assertEquals(2, response.usage().cacheReadTokens());
    }

    @Test
    void retriesOnRateLimitThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport flaky = (url, headers, body) ->
                calls.getAndIncrement() == 0 ? new HttpResult(429, "slow down", Map.of())
                        : new HttpResult(200, OK_JSON, Map.of());
        AnthropicLlmClient client = new AnthropicLlmClient(config("key"), flaky, noStreaming());

        LlmClient.Response response = client.complete(request());

        assertEquals(2, calls.get());
        assertEquals("Hello!", response.content());
    }

    @Test
    void honoursRetryAfterHeaderOverExponentialBackoff() {
        // Exponential backoff here would wait >=5s (initial 10s, full-jitter floor = half). A
        // Retry-After of "0" must short-circuit that, proving the server hint is honoured.
        AnthropicClientConfig slowBackoff = new AnthropicClientConfig("http://api.test", "2023-06-01",
                "key", Duration.ofSeconds(5), 3, 10_000, 20_000, 2.0, true);
        AtomicInteger calls = new AtomicInteger();
        HttpTransport flaky = (url, headers, body) ->
                calls.getAndIncrement() == 0
                        ? new HttpResult(429, "slow down", Map.of("retry-after", "0"))
                        : new HttpResult(200, OK_JSON, Map.of());
        AnthropicLlmClient client = new AnthropicLlmClient(slowBackoff, flaky, noStreaming());

        long start = System.nanoTime();
        LlmClient.Response response = client.complete(request());
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(2, calls.get());
        assertEquals("Hello!", response.content());
        assertTrue(elapsedMillis < 3_000,
                "Retry-After: 0 should skip the multi-second exponential backoff, was " + elapsedMillis + "ms");
    }

    @Test
    void malformedRetryAfterFallsBackToBackoffAndStillSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport flaky = (url, headers, body) ->
                calls.getAndIncrement() == 0
                        ? new HttpResult(429, "slow down", Map.of("retry-after", "soon"))
                        : new HttpResult(200, OK_JSON, Map.of());
        AnthropicLlmClient client = new AnthropicLlmClient(config("key"), flaky, noStreaming());

        LlmClient.Response response = client.complete(request());

        assertEquals(2, calls.get());
        assertEquals("Hello!", response.content());
    }

    @Test
    void throwsAfterExhaustingRetries() {
        HttpTransport always500 = (url, headers, body) -> new HttpResult(500, "boom", Map.of());
        AnthropicLlmClient client = new AnthropicLlmClient(config("key"), always500, noStreaming());

        assertThrows(LlmClientException.class, () -> client.complete(request()));
    }

    @Test
    void promptCachingShapesSystemAsBlockWithCacheControl() {
        AtomicReference<String> sentBody = new AtomicReference<>();
        HttpTransport capture = (url, headers, body) -> {
            sentBody.set(body);
            return new HttpResult(200, OK_JSON, Map.of());
        };
        AnthropicLlmClient client = new AnthropicLlmClient(config("key"), capture, noStreaming());

        client.complete(new LlmClient.Request(new ModelId("m"), "SYSTEM",
                List.of(new LlmClient.Message(LlmClient.Role.USER, "hi")), 100, null, true, Map.of()));

        assertTrue(sentBody.get().contains("cache_control"));
        assertTrue(sentBody.get().contains("ephemeral"));
    }

    @Test
    void missingApiKeyThrowsBeforeSending() {
        HttpTransport unused = (url, headers, body) -> {
            throw new AssertionError("should not be called");
        };
        AnthropicLlmClient client = new AnthropicLlmClient(config(""), unused, noStreaming());

        assertThrows(LlmClientException.class, () -> client.complete(request()));
    }

    @Test
    void streamAssemblesTokensAndFinalResponse() {
        StreamingTransport sse = (url, headers, body) -> Stream.of(
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"m\",\"usage\":{\"input_tokens\":5}}}",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}",
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}",
                "data: {\"type\":\"message_stop\"}");
        AnthropicLlmClient client = new AnthropicLlmClient(config("key"),
                (url, headers, body) -> new HttpResult(200, OK_JSON, Map.of()), sse);

        StringBuilder streamed = new StringBuilder();
        LlmClient.Response response = client.stream(request(), streamed::append);

        assertEquals("Hello", streamed.toString());
        assertEquals("Hello", response.content());
        assertEquals(LlmClient.StopReason.END_TURN, response.stopReason());
        assertEquals(5, response.usage().inputTokens());
        assertEquals(2, response.usage().outputTokens());
    }
}
