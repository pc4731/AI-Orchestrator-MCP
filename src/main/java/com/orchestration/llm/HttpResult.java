package com.orchestration.llm;

import java.util.Map;

/** Minimal HTTP response value used by {@link HttpTransport}. */
public record HttpResult(int statusCode, String body, Map<String, String> headers) {

    public HttpResult {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
