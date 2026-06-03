package com.orchestration.feedback;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/**
 * Delivers the end-of-run retrospective (the team's friction with the orchestrator) to the
 * maintainer. Prefers email when SMTP and a recipient are configured; otherwise — and on any send
 * failure — appends to a local backlog file so the feedback is never lost. Returns a human-readable
 * status that the MCP layer relays back so the user knows where the report went.
 */
public class FeedbackReporter {

    private final JavaMailSender mailer; // null when SMTP is not configured
    private final boolean enabled;
    private final String to;
    private final String from;
    private final Path backlogFile;

    public FeedbackReporter(JavaMailSender mailer, boolean enabled, String to, String from,
                            Path backlogFile) {
        this.mailer = mailer;
        this.enabled = enabled;
        this.to = to == null ? "" : to.trim();
        this.from = (from == null || from.isBlank()) ? "agent-orchestrator@localhost" : from;
        this.backlogFile = Objects.requireNonNull(backlogFile, "backlogFile");
    }

    /** Deliver the report; returns a status describing where it went (or why it didn't). */
    public String deliver(String projectId, String projectState, String reportMarkdown) {
        if (!enabled) {
            return "feedback disabled";
        }
        if (reportMarkdown == null || reportMarkdown.isBlank()) {
            return "no feedback to send";
        }
        String subject = "[orchestrator feedback] project " + projectState + " — improvement notes";
        String body = "Project: " + projectId + "\nOutcome: " + projectState + "\nTime: "
                + Instant.now() + "\n\n" + reportMarkdown;

        if (mailer != null && !to.isBlank()) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(to);
                message.setFrom(from);
                message.setSubject(subject);
                message.setText(body);
                mailer.send(message);
                return "emailed to " + to;
            } catch (RuntimeException e) {
                String saved = appendToBacklog(subject, body);
                return "email failed (" + e.getMessage() + "); " + saved;
            }
        }
        return appendToBacklog(subject, body);
    }

    private String appendToBacklog(String subject, String body) {
        try {
            if (backlogFile.getParent() != null) {
                Files.createDirectories(backlogFile.getParent());
            }
            String entry = "\n\n---\n## " + subject + "\n\n" + body + "\n";
            Files.writeString(backlogFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return "appended to " + backlogFile + (to.isBlank()
                    ? " (set feedback.to + SMTP to email instead)" : "");
        } catch (IOException e) {
            return "could not deliver feedback: " + e.getMessage();
        }
    }
}
