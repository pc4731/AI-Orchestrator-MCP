package com.orchestration.learning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LessonPackTest {

    @Test
    void writesAndReadsBackAPortablePack(@TempDir Path dir) throws IOException {
        LessonPack.Bundle bundle = new LessonPack.Bundle("2026-06-05T10:00:00Z", "export", List.of(
                new LessonPack.Entry("BACKEND_DEVELOPER", "Run the build before submitting."),
                new LessonPack.Entry("QA_ENGINEER", "Check edge cases.")));
        Path file = dir.resolve("nested").resolve("pack.json"); // parent dirs are created

        LessonPack.write(file, bundle);
        LessonPack.Bundle read = LessonPack.read(file);

        assertEquals(2, read.lessons().size());
        assertEquals("BACKEND_DEVELOPER", read.lessons().get(0).role());
        assertEquals("Run the build before submitting.", read.lessons().get(0).content());
        assertEquals("export", read.source());
    }
}
