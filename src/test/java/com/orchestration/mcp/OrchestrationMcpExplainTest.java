package com.orchestration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestration.artifact.ArtifactRepository;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.knowledge.ProjectKnowledgeStore;
import com.orchestration.memory.SqliteMemoryStore;
import com.orchestration.web.ActiveProject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the "explain a prebuilt project" tool with the test standing in for Claude Code: explain →
 * pull the PROJECT_EXPLAINER task → submit the explanation, and verify it is (optionally) saved as
 * the project brief so a future session has context.
 */
class OrchestrationMcpExplainTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void explainsThePrebuiltProjectAndSavesBriefWhenRemembering() throws Exception {
        McpBridge bridge = new McpBridge();
        InMemoryRepo repo = new InMemoryRepo();
        ProjectKnowledgeStore store = new ProjectKnowledgeStore(repo);
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            OrchestrationMcpService service = new OrchestrationMcpService(
                    neverCalledEngine(), bridge, memory, new ActiveProject(), store, "/workspace");

            Map<String, Object> started = service.explain("/path/to/app", "what does the API do", true);
            assertEquals("CALL_NEXT", started.get("nextAction"));

            // Claude pulls the explainer task — it targets the given path and is read-only.
            Map<String, Object> next = pollUntilTask(service);
            Map<?, ?> task = (Map<?, ?>) next.get("task");
            assertEquals("PROJECT_EXPLAINER", task.get("role"));
            assertTrue(String.valueOf(task.get("instructions")).contains("/path/to/app"));
            String taskId = String.valueOf(task.get("taskId"));

            // Claude explains and submits the result.
            Map<String, Object> result = service.submit(taskId, mapper.readTree(
                    "{\"status\":\"COMPLETED\",\"output\":{\"explanation\":\"# It is a todo API\"}}"));

            assertEquals("STOP", result.get("nextAction"));
            assertEquals(Boolean.TRUE, result.get("savedAsProjectBrief"));
            assertEquals("# It is a todo API",
                    repo.read(ProjectKnowledgeStore.DEFAULT_PATH).orElseThrow());
        } finally {
            memory.close();
        }
    }

    @Test
    void explainWithoutRememberDoesNotWriteABrief() throws Exception {
        McpBridge bridge = new McpBridge();
        InMemoryRepo repo = new InMemoryRepo();
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            OrchestrationMcpService service = new OrchestrationMcpService(
                    neverCalledEngine(), bridge, memory, new ActiveProject(),
                    new ProjectKnowledgeStore(repo), "/workspace");

            service.explain(null, null, false); // default path = workspace, do not remember
            Map<String, Object> next = pollUntilTask(service);
            String taskId = String.valueOf(((Map<?, ?>) next.get("task")).get("taskId"));

            Map<String, Object> result = service.submit(taskId, mapper.readTree(
                    "{\"status\":\"COMPLETED\",\"output\":{\"explanation\":\"# Some app\"}}"));

            assertEquals(Boolean.FALSE, result.get("savedAsProjectBrief"));
            assertTrue(repo.files.isEmpty(), "no brief is written when rememberProject is false");
        } finally {
            memory.close();
        }
    }

    private Map<String, Object> pollUntilTask(OrchestrationMcpService service) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> next = service.next();
            if (next.containsKey("task")) {
                return next;
            }
        }
        throw new AssertionError("no explainer task was offered");
    }

    private static OrchestrationEngine neverCalledEngine() {
        return new OrchestrationEngine() {
            @Override public ProjectHandle submit(ProjectRequest request) { throw new UnsupportedOperationException(); }
            @Override public ProjectHandle resume(String projectId) { throw new UnsupportedOperationException(); }
            @Override public ProjectStatus status(String projectId) { throw new UnsupportedOperationException(); }
            @Override public void decideGate(String gateId, GateDecision decision) { throw new UnsupportedOperationException(); }
            @Override public void shutdown() { /* no-op */ }
        };
    }

    /** Minimal in-memory ArtifactRepository: keeps the last-written content per path. */
    private static final class InMemoryRepo implements ArtifactRepository {
        final Map<String, String> files = new LinkedHashMap<>();

        @Override public CommitId write(WriteRequest request) {
            for (FileChange c : request.changes()) {
                files.put(c.path(), c.content());
            }
            return new CommitId("sha-" + files.size());
        }

        @Override public Optional<String> read(String path) {
            return Optional.ofNullable(files.get(path));
        }

        @Override public List<String> list(String pathPrefix) {
            List<String> out = new ArrayList<>();
            files.keySet().forEach(p -> { if (p.startsWith(pathPrefix)) out.add(p); });
            return out;
        }
    }
}
