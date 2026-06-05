package com.orchestration.learning;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The proposals inbox: a git-trackable JSONL file of {@link Lesson}s mined from runs, each PENDING
 * until the user decides. Dedup is built in — recording a lesson that already exists (same
 * {@link Lesson#signature()}) bumps its recurrence instead of adding a duplicate, so a recurring
 * problem accrues weight rather than spamming the inbox. Best-effort I/O: never breaks a run.
 */
public class LessonStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public LessonStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Record a proposal, deduped by signature (recurring lessons bump recurrence). */
    public synchronized void record(Lesson lesson) {
        List<Lesson> all = all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).signature().equals(lesson.signature())) {
                all.set(i, all.get(i).withRecurrence(all.get(i).recurrence() + 1));
                writeAll(all);
                return;
            }
        }
        all.add(lesson);
        writeAll(all);
    }

    /** Set a proposal's status (APPROVED/REJECTED). Returns false if the id is unknown. */
    public synchronized boolean decide(String id, String status) {
        List<Lesson> all = all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(id)) {
                all.set(i, all.get(i).withStatus(status));
                writeAll(all);
                return true;
            }
        }
        return false;
    }

    public synchronized Optional<Lesson> get(String id) {
        return all().stream().filter(l -> l.id().equals(id)).findFirst();
    }

    public synchronized List<Lesson> pending() {
        return all().stream().filter(l -> Lesson.PENDING.equals(l.status())).toList();
    }

    public synchronized List<Lesson> all() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new ArrayList<>();
        }
        List<Lesson> out = new ArrayList<>();
        for (String line : lines) {
            String l = line.strip();
            if (l.isEmpty()) {
                continue;
            }
            try {
                out.add(mapper.readValue(l, Lesson.class));
            } catch (IOException ignored) {
                // skip a corrupt line rather than failing the whole read
            }
        }
        return out;
    }

    private void writeAll(List<Lesson> lessons) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            StringBuilder sb = new StringBuilder();
            for (Lesson l : lessons) {
                sb.append(mapper.writeValueAsString(l)).append(System.lineSeparator());
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Never fail a run because the proposals inbox couldn't be written.
        }
    }
}
