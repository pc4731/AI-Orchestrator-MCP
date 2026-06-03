package com.orchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the orchestrator self-improvement feedback (bound from {@code feedback}). After a run,
 * the Retrospective Analyst's improvement notes are emailed to {@code to} (when SMTP and a recipient
 * are configured) and, failing that, appended to {@code backlogFile} so they are never lost.
 */
@ConfigurationProperties("feedback")
public record FeedbackProperties(Boolean enabled, String to, String from, String backlogFile) {

    public FeedbackProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        from = (from == null || from.isBlank()) ? "agent-orchestrator@localhost" : from;
        backlogFile = (backlogFile == null || backlogFile.isBlank())
                ? "feedback/improvements.md" : backlogFile;
        to = to == null ? "" : to;
    }

    public boolean active() {
        return Boolean.TRUE.equals(enabled);
    }
}
