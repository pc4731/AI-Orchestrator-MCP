package com.orchestration.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Append-only trend log of {@link RunMetrics}, one JSON object per line. Persisting a summary per run
 * (rather than keeping it in memory) means the trend survives restarts, so you can actually see whether
 * rework is falling over weeks of projects. Best-effort: a metrics I/O error never breaks a run.
 */
public class MetricsStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetricsStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Append one run's tally as a JSON line. */
    public synchronized void record(RunMetrics metrics) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String line = mapper.writeValueAsString(metrics) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Never fail a build run because metrics couldn't be written.
        }
    }

    /** The most recent {@code n} runs, oldest first. */
    public List<RunMetrics> recent(int n) {
        if (n <= 0 || !Files.exists(file)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        List<RunMetrics> out = new ArrayList<>();
        for (int i = Math.max(0, lines.size() - n); i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                out.add(mapper.readValue(line, RunMetrics.class));
            } catch (IOException ignored) {
                // skip a corrupt line rather than failing the whole read
            }
        }
        return out;
    }
}
