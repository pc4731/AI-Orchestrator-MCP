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

    @Test
    void promoteLearnedWritesARoleSkillThatResolveForRoleThenAttaches() {
        SkillRegistry registry = new SkillRegistry(dir);

        // No learned skill yet: resolveForRole returns only the explicit skills.
        assertFalse(registry.resolveForRole(AgentRole.BACKEND_DEVELOPER, List.of()).contains("Always run tests"));

        registry.promoteLearned(AgentRole.BACKEND_DEVELOPER, "Always run tests before submitting.");

        String resolved = registry.resolveForRole(AgentRole.BACKEND_DEVELOPER, List.of());
        assertTrue(resolved.contains("Always run tests before submitting."),
                "an approved lesson must auto-attach to that role on future runs");
        assertTrue(Files.isRegularFile(dir.resolve("learned").resolve("BACKEND_DEVELOPER.md")));
    }

    @Test
    void learnedSkillIsScopedToItsRole() {
        SkillRegistry registry = new SkillRegistry(dir);
        registry.promoteLearned(AgentRole.QA_ENGINEER, "QA-specific lesson.");

        assertTrue(registry.resolveForRole(AgentRole.QA_ENGINEER, List.of()).contains("QA-specific lesson."));
        assertFalse(registry.resolveForRole(AgentRole.BACKEND_DEVELOPER, List.of()).contains("QA-specific lesson."),
                "a learned skill must not leak to other roles");
    }

    @Test
    void clearLearnedRemovesIt() {
        SkillRegistry registry = new SkillRegistry(dir);
        registry.promoteLearned(AgentRole.BACKEND_DEVELOPER, "a lesson");
        assertTrue(registry.hasLearned(AgentRole.BACKEND_DEVELOPER));

        registry.clearLearned(AgentRole.BACKEND_DEVELOPER);

        assertFalse(registry.hasLearned(AgentRole.BACKEND_DEVELOPER));
        assertFalse(registry.resolveForRole(AgentRole.BACKEND_DEVELOPER, List.of()).contains("a lesson"));
    }

    @Test
    void addSynthesizedSkillWritesItAndAttachesItToEachRole() {
        SkillRegistry registry = new SkillRegistry(dir);

        String slug = registry.addSynthesizedSkill("AEM Development",
                "Build AEM components with Sling Models and HTL.",
                List.of(AgentRole.FRONTEND_DEVELOPER, AgentRole.BACKEND_DEVELOPER));

        assertEquals("aem-development", slug, "the name is slugified");
        assertTrue(Files.isRegularFile(dir.resolve("aem-development.md")), "skill file persists for reuse");
        // It folds into both attached roles' prompts — picked up by any agent created afterwards.
        assertTrue(registry.resolveForRole(AgentRole.FRONTEND_DEVELOPER, List.of()).contains("Sling Models"));
        assertTrue(registry.resolveForRole(AgentRole.BACKEND_DEVELOPER, List.of()).contains("Sling Models"));
        // And NOT into an unrelated role.
        assertFalse(registry.resolveForRole(AgentRole.QA_ENGINEER, List.of()).contains("Sling Models"),
                "a synthesized skill only reaches the roles it was attached to");
    }

    @Test
    void synthesizedAttachmentSurvivesAFreshRegistryInstance() {
        new SkillRegistry(dir).addSynthesizedSkill("salesforce-apex", "Apex governor limits matter.",
                List.of(AgentRole.BACKEND_DEVELOPER));

        // A brand-new registry over the same dir = the "future run / new session" case.
        SkillRegistry reopened = new SkillRegistry(dir);
        assertTrue(reopened.resolveForRole(AgentRole.BACKEND_DEVELOPER, List.of()).contains("governor limits"),
                "an approved domain skill is reused by future runs without re-researching");
        assertEquals(List.of("salesforce-apex"), reopened.attachedNames(AgentRole.BACKEND_DEVELOPER));
    }

    @Test
    void attachingTheSameSkillTwiceIsIdempotent() {
        SkillRegistry registry = new SkillRegistry(dir);
        registry.addSynthesizedSkill("aem", "x", List.of(AgentRole.FRONTEND_DEVELOPER));
        registry.addSynthesizedSkill("aem", "x", List.of(AgentRole.FRONTEND_DEVELOPER));
        assertEquals(List.of("aem"), registry.attachedNames(AgentRole.FRONTEND_DEVELOPER));
    }

    @Test
    void learnedSkillsListsEveryRolesLearnedFileForExport() {
        SkillRegistry registry = new SkillRegistry(dir);
        registry.promoteLearned(AgentRole.BACKEND_DEVELOPER, "dev lesson");
        registry.promoteLearned(AgentRole.QA_ENGINEER, "qa lesson");

        var learned = registry.learnedSkills();

        assertEquals(2, learned.size());
        assertTrue(learned.get(AgentRole.BACKEND_DEVELOPER).contains("dev lesson"));
        assertTrue(learned.get(AgentRole.QA_ENGINEER).contains("qa lesson"));
    }
}
