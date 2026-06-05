package com.orchestration.learning;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A portable bundle of approved, learned skills — so learnings can move between SEPARATE installs of
 * the tool (git already carries them between machines that share the repo). Export writes one JSON
 * file; import re-stages each entry as a PENDING proposal so it passes back through the approval gate
 * on the receiving machine — an imported pack never silently changes behavior.
 */
public final class LessonPack {

    /** One learned skill in a pack: the role it applies to and its full content. */
    public record Entry(String role, String content) {}

    /** A pack file: when/where it came from and the learned skills it carries. */
    public record Bundle(String createdAt, String source, List<Entry> lessons) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LessonPack() {
    }

    public static void write(Path file, Bundle bundle) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), bundle);
    }

    public static Bundle read(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), Bundle.class);
    }
}
