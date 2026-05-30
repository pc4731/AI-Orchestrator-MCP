package com.orchestration.llm;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Seam over the streaming (Server-Sent Events) POST used by {@link AnthropicLlmClient#stream}.
 * Returns the response body as a lazy stream of lines. The production implementation uses
 * {@code HttpClient.send(..., BodyHandlers.ofLines())}; tests supply canned SSE lines.
 */
@FunctionalInterface
public interface StreamingTransport {

    Stream<String> send(String url, Map<String, String> headers, String body) throws IOException, InterruptedException;
}
