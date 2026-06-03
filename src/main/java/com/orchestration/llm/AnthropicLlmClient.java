package com.orchestration.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Thin Anthropic Claude client built on {@code java.net.http} (no third-party SDK).
 *
 * <p>Responsibilities hidden from agents: building the Messages API request (including
 * Anthropic prompt caching of the system prompt), retry on 429/5xx honouring the server's
 * Retry-After hint and otherwise using jittered exponential backoff,
 * token accounting, and Server-Sent-Events streaming. The HTTP calls go through the
 * {@link HttpTransport}/{@link StreamingTransport} seams so the client is fully unit-testable
 * without network access or an API key.
 */
public class AnthropicLlmClient implements LlmClient {

    private static final int DEFAULT_MAX_TOKENS = 1024;
    private static final String MESSAGES_PATH = "/v1/messages";

    private final AnthropicClientConfig config;
    private final HttpTransport transport;
    private final StreamingTransport streamingTransport;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicLlmClient(AnthropicClientConfig config,
                              HttpTransport transport,
                              StreamingTransport streamingTransport) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.streamingTransport = Objects.requireNonNull(streamingTransport, "streamingTransport");
    }

    /** Build a client backed by a real {@link HttpClient}. */
    public static AnthropicLlmClient create(AnthropicClientConfig config) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
        HttpTransport transport = (url, headers, body) -> {
            HttpResponse<String> response = http.send(buildHttpRequest(url, headers, body, config),
                    HttpResponse.BodyHandlers.ofString());
            // Capture Retry-After so the retry loop can honour the server's own back-off hint on 429/529.
            Map<String, String> responseHeaders = response.headers().firstValue("retry-after")
                    .map(v -> Map.of("retry-after", v))
                    .orElseGet(Map::of);
            return new HttpResult(response.statusCode(), response.body(), responseHeaders);
        };
        StreamingTransport streaming = (url, headers, body) ->
                http.send(buildHttpRequest(url, headers, body, config), HttpResponse.BodyHandlers.ofLines()).body();
        return new AnthropicLlmClient(config, transport, streaming);
    }

    private static HttpRequest buildHttpRequest(String url, Map<String, String> headers, String body,
                                                AnthropicClientConfig config) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return builder.build();
    }

    // ------------------------------------------------------------------------
    // LlmClient
    // ------------------------------------------------------------------------

    @Override
    public Response complete(Request request) {
        Objects.requireNonNull(request, "request");
        String body = buildRequestBody(request, false);
        HttpResult result = sendWithRetry(body);
        return parseResponse(result.body());
    }

    @Override
    public Response stream(Request request, StreamHandler handler) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        requireApiKey();
        String body = buildRequestBody(request, true);
        String url = config.baseUrl() + MESSAGES_PATH;

        StringBuilder content = new StringBuilder();
        StreamAccumulator acc = new StreamAccumulator();
        try (Stream<String> lines = streamingTransport.send(url, headers(true), body)) {
            lines.forEach(line -> processSseLine(line, request, handler, content, acc));
        } catch (IOException e) {
            LlmClientException ex = new LlmClientException("Streaming I/O error", e);
            handler.onError(ex);
            throw ex;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("Interrupted during streaming", e);
        }

        Response response = new Response(
                content.toString(),
                acc.model != null ? acc.model : request.model(),
                acc.stopReason != null ? acc.stopReason : StopReason.END_TURN,
                new TokenUsage(acc.inputTokens, acc.outputTokens, 0, 0),
                Optional.ofNullable(acc.id));
        handler.onComplete(response);
        return response;
    }

    // ------------------------------------------------------------------------
    // Request / response (de)serialisation
    // ------------------------------------------------------------------------

    private String buildRequestBody(Request request, boolean stream) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", request.model().value());
        root.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : DEFAULT_MAX_TOKENS);
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        root.put("stream", stream);

        if (request.system() != null && !request.system().isBlank()) {
            if (request.cacheSystemPrompt() && config.promptCacheEnabled()) {
                // Mark the (stable) system prompt cacheable so repeated calls reuse it.
                ArrayNode systemBlocks = mapper.createArrayNode();
                ObjectNode block = mapper.createObjectNode();
                block.put("type", "text");
                block.put("text", request.system());
                ObjectNode cacheControl = mapper.createObjectNode();
                cacheControl.put("type", "ephemeral");
                block.set("cache_control", cacheControl);
                systemBlocks.add(block);
                root.set("system", systemBlocks);
            } else {
                root.put("system", request.system());
            }
        }

        ArrayNode messages = mapper.createArrayNode();
        for (Message message : request.messages()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("role", message.role() == Role.USER ? "user" : "assistant");
            node.put("content", message.content());
            messages.add(node);
        }
        root.set("messages", messages);

        request.options().forEach(root::putPOJO);

        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new LlmClientException("Failed to serialise request", e);
        }
    }

    private Response parseResponse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            StringBuilder content = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    content.append(block.path("text").asText());
                }
            }
            JsonNode usage = root.path("usage");
            return new Response(
                    content.toString(),
                    new ModelId(root.path("model").asText("unknown")),
                    mapStopReason(root.path("stop_reason").asText(null)),
                    new TokenUsage(
                            usage.path("input_tokens").asLong(0),
                            usage.path("output_tokens").asLong(0),
                            usage.path("cache_read_input_tokens").asLong(0),
                            usage.path("cache_creation_input_tokens").asLong(0)),
                    Optional.ofNullable(root.path("id").asText(null)));
        } catch (JsonProcessingException e) {
            throw new LlmClientException("Failed to parse response: " + json, e);
        }
    }

    private void processSseLine(String line, Request request, StreamHandler handler,
                                StringBuilder content, StreamAccumulator acc) {
        if (line == null || !line.startsWith("data:")) {
            return;
        }
        String json = line.substring("data:".length()).trim();
        if (json.isEmpty() || "[DONE]".equals(json)) {
            return;
        }
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return; // ignore malformed SSE payloads
        }
        switch (node.path("type").asText()) {
            case "message_start" -> {
                JsonNode message = node.path("message");
                acc.model = new ModelId(message.path("model").asText(request.model().value()));
                acc.id = message.path("id").asText(null);
                acc.inputTokens = message.path("usage").path("input_tokens").asLong(0);
            }
            case "content_block_delta" -> {
                String text = node.path("delta").path("text").asText("");
                if (!text.isEmpty()) {
                    content.append(text);
                    handler.onToken(text);
                }
            }
            case "message_delta" -> {
                String stopReason = node.path("delta").path("stop_reason").asText(null);
                if (stopReason != null) {
                    acc.stopReason = mapStopReason(stopReason);
                }
                acc.outputTokens = node.path("usage").path("output_tokens").asLong(acc.outputTokens);
            }
            default -> {
                // message_stop, ping, content_block_start/stop — nothing to accumulate
            }
        }
    }

    private StopReason mapStopReason(String raw) {
        return switch (raw) {
            case "end_turn" -> StopReason.END_TURN;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "stop_sequence" -> StopReason.STOP_SEQUENCE;
            case "tool_use" -> StopReason.TOOL_USE;
            case "refusal" -> StopReason.REFUSAL;
            case null, default -> StopReason.END_TURN;
        };
    }

    // ------------------------------------------------------------------------
    // Transport with retry
    // ------------------------------------------------------------------------

    private HttpResult sendWithRetry(String body) {
        requireApiKey();
        String url = config.baseUrl() + MESSAGES_PATH;
        Map<String, String> headers = headers(false);
        int maxRetries = config.maxRetries();
        LlmClientException lastFailure = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResult result = transport.send(url, headers, body);
                if (result.statusCode() >= 200 && result.statusCode() < 300) {
                    return result;
                }
                if (isRetryable(result.statusCode()) && attempt < maxRetries) {
                    sleepBackoff(attempt, result);
                    continue;
                }
                throw new LlmClientException(
                        "Anthropic API error " + result.statusCode() + ": " + result.body(), result.statusCode());
            } catch (IOException e) {
                lastFailure = new LlmClientException("HTTP transport error", e);
                if (attempt < maxRetries) {
                    sleepBackoff(attempt, null);
                    continue;
                }
                throw lastFailure;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmClientException("Interrupted while calling Anthropic API", e);
            }
        }
        throw lastFailure != null ? lastFailure : new LlmClientException("Request failed");
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    /** Wait before a retry: honour the server's Retry-After hint when present, otherwise use jittered
     *  exponential backoff. {@code result} may be null (transport-level I/O error, no response). */
    private void sleepBackoff(int attempt, HttpResult result) {
        long backoff = retryAfterMillis(result).orElseGet(() -> exponentialBackoff(attempt));
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("Interrupted during backoff", e);
        }
    }

    /** Full-jitter exponential backoff: a random wait in [base/2, base] so many agents retrying after
     *  the same 429 don't reconverge into a synchronized thundering herd. */
    private long exponentialBackoff(int attempt) {
        long base = Math.min(config.maxBackoffMillis(),
                (long) (config.initialBackoffMillis() * Math.pow(config.multiplier(), attempt)));
        long half = base / 2;
        return half + ThreadLocalRandom.current().nextLong(half + 1);
    }

    /** Parse the Retry-After header (delta-seconds form, which Anthropic uses) into millis, bounded by
     *  the configured max backoff so a bad header can't park a worker indefinitely. HTTP-date form is
     *  not honoured — we fall back to exponential backoff for it. */
    private Optional<Long> retryAfterMillis(HttpResult result) {
        if (result == null) {
            return Optional.empty();
        }
        String header = result.headers().get("retry-after");
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return Optional.of(Math.min(config.maxBackoffMillis(), Math.max(0L, seconds) * 1000L));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Map<String, String> headers(boolean streaming) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", config.apiKey());
        headers.put("anthropic-version", config.anthropicVersion());
        headers.put("content-type", "application/json");
        headers.put("accept", streaming ? "text/event-stream" : "application/json");
        return headers;
    }

    private void requireApiKey() {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new LlmClientException("ANTHROPIC_API_KEY is not configured");
        }
    }

    /** Mutable holder for state accumulated while consuming the SSE stream. */
    private static final class StreamAccumulator {
        private ModelId model;
        private StopReason stopReason;
        private String id;
        private long inputTokens;
        private long outputTokens;
    }
}
