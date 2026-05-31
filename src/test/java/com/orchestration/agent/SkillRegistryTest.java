package com.orchestration.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {

    @TempDir
    Path dir;

    private void writeSkill(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name + ".md"), content);
    }

    @Test
    void getReadsSkillByNameWithOrWithoutExtension() throws IOException {
        writeSkill("accessibility", "WCAG AA");
        SkillRegistry registry = new SkillRegistry(dir);
        assertEquals("WCAG AA", registry.get("accessibility").orElseThrow());
        assertEquals("WCAG AA", registry.get("accessibility.md").orElseThrow());
    }

    @Test
    void resolveConcatenatesNamedSkillsUnderHeadings() throws IOException {
        writeSkill("a", "alpha");
        writeSkill("b", "beta");
        String resolved = new SkillRegistry(dir).resolve(List.of("a", "b"));
        assertTrue(resolved.contains("## Skill: a"));
        assertTrue(resolved.contains("alpha"));
        assertTrue(resolved.contains("## Skill: b"));
        assertTrue(resolved.contains("beta"));
    }

    @Test
    void unknownSkillsAreSkippedNotFatal() throws IOException {
        writeSkill("known", "here");
        String resolved = new SkillRegistry(dir).resolve(List.of("known", "missing"));
        assertTrue(resolved.contains("here"));
        assertFalse(resolved.contains("missing"));
    }

    @Test
    void resolveEmptyForNoNames() {
        assertEquals("", new SkillRegistry(dir).resolve(List.of()));
        assertEquals("", SkillRegistry.empty().resolve(List.of("anything")));
    }

    @Test
    void availableListsMarkdownFiles() throws IOException {
        writeSkill("one", "1");
        writeSkill("two", "2");
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        assertEquals(List.of("one", "two"), new SkillRegistry(dir).available());
    }

    @Test
    void appendSkipsBlankExtra() {
        assertEquals("base", AgentPrompts.append("base", ""));
        assertEquals("base", AgentPrompts.append("base", null));
        assertEquals("base\n\nextra", AgentPrompts.append("base", "extra"));
    }
}
