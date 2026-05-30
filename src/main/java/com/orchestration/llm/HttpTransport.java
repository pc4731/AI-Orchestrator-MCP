package com.orchestration.llm;

import java.io.IOException;
import java.util.Map;

/**
 * Seam over the blocking HTTP POST used by {@link AnthropicLlmClient#complete}. The production
 * implementation wraps {@code java.net.http.HttpClient}; tests supply a fake so the client can be
 * exercised (parsing, retries, request shape) without network access or an API key.
 */
@FunctionalInterface
public interface HttpTransport {

    HttpResult send(String url, Map<String, String> headers, String body) throws IOException, InterruptedException;
}
