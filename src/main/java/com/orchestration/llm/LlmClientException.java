package com.orchestration.llm;

/** Unchecked failure from the {@link LlmClient} (transport error, non-retryable API error, etc.). */
public class LlmClientException extends RuntimeException {

    private final int statusCode;

    public LlmClientException(String message) {
        this(message, 0);
    }

    public LlmClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public LlmClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    /** The HTTP status that triggered the failure, or {@code 0} if not HTTP-related. */
    public int statusCode() {
        return statusCode;
    }
}
