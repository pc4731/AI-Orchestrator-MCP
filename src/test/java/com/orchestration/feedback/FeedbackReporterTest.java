package com.orchestration.feedback;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackReporterTest {

    @TempDir
    Path dir;

    @Test
    void appendsToBacklogWhenNoMailerIsConfigured() throws Exception {
        Path backlog = dir.resolve("improvements.md");
        FeedbackReporter reporter = new FeedbackReporter(null, true, "", "from@x", backlog);

        String status = reporter.deliver("p1", "DONE", "- [HIGH] add a lint gate");

        assertTrue(status.startsWith("appended to"), status);
        assertTrue(Files.readString(backlog).contains("add a lint gate"));
    }

    @Test
    void emailsWhenMailerAndRecipientAreConfigured() {
        CapturingMailSender mailer = new CapturingMailSender();
        Path backlog = dir.resolve("improvements.md");
        FeedbackReporter reporter = new FeedbackReporter(mailer, true, "me@team.dev", "bot@x", backlog);

        String status = reporter.deliver("p1", "DONE", "- [HIGH] add a lint gate");

        assertEquals("emailed to me@team.dev", status);
        assertEquals("me@team.dev", mailer.sent.getTo()[0]);
        assertTrue(mailer.sent.getText().contains("add a lint gate"));
        assertFalse(Files.exists(backlog), "a successful email should not also write the backlog file");
    }

    @Test
    void fallsBackToFileWhenEmailFails() throws Exception {
        CapturingMailSender mailer = new CapturingMailSender();
        mailer.fail = true;
        Path backlog = dir.resolve("improvements.md");
        FeedbackReporter reporter = new FeedbackReporter(mailer, true, "me@team.dev", "bot@x", backlog);

        String status = reporter.deliver("p1", "FAILED", "- [HIGH] budget cut the build off");

        assertTrue(status.startsWith("email failed"), status);
        assertTrue(Files.readString(backlog).contains("budget cut the build off"));
    }

    @Test
    void isSilentWhenDisabled() {
        FeedbackReporter reporter = new FeedbackReporter(null, false, "", "from@x",
                dir.resolve("improvements.md"));
        assertEquals("feedback disabled", reporter.deliver("p1", "DONE", "something"));
    }

    /** Minimal JavaMailSender that captures the sent SimpleMailMessage (or fails on demand). */
    private static final class CapturingMailSender implements JavaMailSender {
        SimpleMailMessage sent;
        boolean fail;

        @Override public void send(SimpleMailMessage simpleMessage) {
            if (fail) {
                throw new MailSendException("smtp unavailable");
            }
            this.sent = simpleMessage;
        }

        @Override public void send(SimpleMailMessage... simpleMessages) {
            for (SimpleMailMessage m : simpleMessages) {
                send(m);
            }
        }

        @Override public MimeMessage createMimeMessage() { throw new UnsupportedOperationException(); }
        @Override public MimeMessage createMimeMessage(InputStream is) { throw new UnsupportedOperationException(); }
        @Override public void send(MimeMessage mimeMessage) { throw new UnsupportedOperationException(); }
        @Override public void send(MimeMessage... mimeMessages) { throw new UnsupportedOperationException(); }
        @Override public void send(MimeMessagePreparator p) { throw new UnsupportedOperationException(); }
        @Override public void send(MimeMessagePreparator... p) { throw new UnsupportedOperationException(); }
    }
}
