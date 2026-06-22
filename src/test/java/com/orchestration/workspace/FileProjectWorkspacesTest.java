package com.orchestration.workspace;

import com.orchestration.artifact.ArtifactRepository;
import com.orchestration.workspace.ProjectWorkspaces.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FileProjectWorkspacesTest {

    @Test
    void namesTheFolderFromTheFeatureRequest(@TempDir Path base) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");

        Workspace ws = workspaces.open("p1", "Build a small calculator service");

        assertEquals(base.resolve("build-a-small-calculator-service").toString(), ws.dir());
        assertTrue(Files.isDirectory(base.resolve("build-a-small-calculator-service")));
    }

    @Test
    void aSecondRunOfTheSameIdeaGetsAnIsolatedFolderInsteadOfClobbering(@TempDir Path base) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");

        Workspace first = workspaces.open("p1", "Calculator service");
        Workspace second = workspaces.open("p2", "Calculator service");

        assertNotEquals(first.dir(), second.dir());
        assertEquals(base.resolve("calculator-service").toString(), first.dir());
        assertEquals(base.resolve("calculator-service-2").toString(), second.dir());
    }

    @Test
    void openIsIdempotentPerProjectId(@TempDir Path base) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");

        Workspace a = workspaces.open("p1", "Calculator service");
        Workspace b = workspaces.open("p1", "Calculator service");

        assertEquals(a.dir(), b.dir());
        assertEquals(a.dir(), workspaces.get("p1").dir());
    }

    @Test
    void existingForNameReportsACollisionSoCallersCanOfferEditInPlace(@TempDir Path base) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");

        // No folder yet -> the name is free.
        assertTrue(workspaces.existingForName("Marketing Agent").isEmpty());

        // After a build creates marketing-agent/, the same name (any casing/spacing -> same slug) collides.
        workspaces.open("p1", "Marketing Agent");
        Optional<Path> hit = workspaces.existingForName("marketing agent");
        assertTrue(hit.isPresent());
        assertEquals(base.resolve("marketing-agent").toAbsolutePath().normalize(), hit.get());

        assertTrue(workspaces.existingForName(null).isEmpty());
        assertTrue(workspaces.existingForName("  ").isEmpty());
    }

    @Test
    void directoryOfDoesNotCreateAWorkspace(@TempDir Path base) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");

        assertTrue(workspaces.directoryOf("unknown").isEmpty());

        workspaces.open("p1", "Todo app");
        assertTrue(workspaces.directoryOf("p1").isPresent());
    }

    @Test
    void blankRequestFallsBackToAReadableDefault(@TempDir Path base) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");

        Workspace ws = workspaces.open("p1", "   ");

        assertEquals(base.resolve("project").toString(), ws.dir());
        assertFalse(ws.dir().isBlank());
    }

    @Test
    void resolveExactNameWinsOutrightEvenWithVariantsPresent(@TempDir Path base) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");
        workspaces.open("p1", "Calculator service");   // calculator-service
        workspaces.open("p2", "Calculator service");   // calculator-service-2

        var matches = workspaces.resolveExisting("calculator-service");

        assertEquals(1, matches.size());
        assertEquals(base.resolve("calculator-service"), matches.get(0));
    }

    @Test
    void resolveAmbiguousPrefixReturnsAllCandidatesToDisambiguate(@TempDir Path base) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");
        workspaces.open("p1", "Calculator service");   // calculator-service
        workspaces.open("p2", "Calculator service");   // calculator-service-2

        var matches = workspaces.resolveExisting("calculator"); // no exact folder named "calculator"

        assertEquals(2, matches.size());
    }

    @Test
    void resolveUnknownNameReturnsNothing(@TempDir Path base) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");
        assertTrue(workspaces.resolveExisting("does-not-exist").isEmpty());
    }

    @Test
    void resolveByFullPathTargetsThatDirectory(@TempDir Path base) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");
        Workspace ws = workspaces.open("p1", "Todo app");

        var matches = workspaces.resolveExisting(ws.dir()); // a full path

        assertEquals(1, matches.size());
        assertEquals(base.resolve("todo-app"), matches.get(0));
    }

    @Test
    void openExistingReusesTheSameFolderAndFilesForAnEdit(@TempDir Path base) {
        FileProjectWorkspaces workspaces = new FileProjectWorkspaces(base, true, ".project/knowledge.md");
        Workspace original = workspaces.open("build-run", "Calculator service");
        original.repository().write(new ArtifactRepository.WriteRequest(
                "t1", "BACKEND_DEVELOPER", "add file",
                List.of(new ArtifactRepository.FileChange("src/Calc.java", "class Calc {}"))));

        // A later edit run (new project id) opens the SAME folder and sees the existing file.
        Workspace edit = workspaces.openExisting("edit-run", Path.of(original.dir()));

        assertEquals(original.dir(), edit.dir());
        assertEquals(Optional.of("class Calc {}"), edit.repository().read("src/Calc.java"));
    }
}
