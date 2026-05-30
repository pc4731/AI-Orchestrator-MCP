package com.orchestration.llm;

/**
 * Token accounting for a single LLM call. Feeds the cost/token budget manager and the audit log.
 * Cache read/write counters are tracked separately so the savings from Anthropic prompt caching
 * are visible.
 */
public record TokenUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens
) {

    public long total() {
        return inputTokens + outputTokens;
    }

    public static TokenUsage zero() {
        return new TokenUsage(0, 0, 0, 0);
    }

    /** Combine two usages (e.g. when aggregating per-task into per-project totals). */
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheReadTokens + other.cacheReadTokens,
                cacheWriteTokens + other.cacheWriteTokens);
    }
}
