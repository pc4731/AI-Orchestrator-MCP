package com.orchestration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestration.agent.AgentFactory;
import com.orchestration.audit.InMemoryAuditLog;
import com.orchestration.config.AgentDefinition;
import com.orchestration.config.AgentsProperties;
import com.orchestration.engine.AgentTaskProcessor;
import com.orchestration.engine.DefaultOrchestrationEngine;
import com.orchestration.engine.TeamLeadProjectPlanner;
import com.orchestration.artifact.JGitArtifactRepository;
import com.orchestration.memory.SqliteMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the full MCP loop with the test itself standing in for Claude Code: start the project, then
 * repeatedly pull a task and submit a canned result, until the project is DONE — proving the engine
 * runs entirely on an external brain (no LLM, no API key) and still commits real code to Git.
 */
class OrchestrationMcpServiceTest {

    @TempDir
    Path repoDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private static AgentsProperties agents() {
        return new AgentsProperties(Map.of(
                "team-lead", new AgentDefinition("TEAM_LEAD", "x", null, List.of("DECOMPOSE_TASKS"), 4096, 0.2),
                "backend-architect", new AgentDefinition("BACKEND_ARCHITECT", "x", null,
                        List.of("DESIGN_ARCHITECTURE"), 4096, 0.2),
                "backend-developer", new AgentDefinition("BACKEND_DEVELOPER", "x", null,
                        List.of("WRITE_CODE"), 4096, 0.2)));
    }

    private String cannedResult(String role) {
        return switch (role) {
            case "TEAM_LEAD" -> "{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\",\"output\":{\"tasks\":["
                    + "{\"id\":\"a\",\"title\":\"Design\",\"description\":\"d\",\"role\":\"BACKEND_ARCHITECT\",\"dependsOn\":[]},"
                    + "{\"id\":\"b\",\"title\":\"Build\",\"description\":\"d\",\"role\":\"BACKEND_DEVELOPER\",\"dependsOn\":[\"a\"]}]}}";
            case "BACKEND_DEVELOPER" -> "{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\",\"artifacts\":["
                    + "{\"path\":\"src/App.java\",\"content\":\"class App {}\"}]}";
            default -> "{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\",\"output\":{\"design\":\"layered\"}}";
        };
    }

    @Test
    void claudeCodeDrivesTheTeamToCommittedCode() throws Exception {
        McpBridge bridge = new McpBridge();
        AgentFactory factory = new McpAgentFactory(agents(), bridge);
        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            JGitArtifactRepository repo = new JGitArtifactRepository(repoDir);
            InMemoryAuditLog audit = new InMemoryAuditLog();
            DefaultOrchestrationEngine engine = new DefaultOrchestrationEngine(
                    new TeamLeadProjectPlanner(factory),
                    new AgentTaskProcessor(factory, repo, audit),
                    memory, audit);

            OrchestrationMcpService service = new OrchestrationMcpService(
                    engine, bridge, memory, new com.orchestration.web.ActiveProject());

            Map<String, Object> started = service.start("Build a todo app");
            assertTrue(started.containsKey("projectId"), "start should return a projectId");

            // Play Claude Code: pull tasks and submit canned results until the project settles.
            String state = "PLANNING";
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                Map<String, Object> next = service.next();
                Object taskObj = next.get("task");
                if (taskObj instanceof Map<?, ?> task) {
                    String taskId = String.valueOf(task.get("taskId"));
                    String role = String.valueOf(task.get("role"));
                    service.submit(taskId, mapper.readTree(cannedResult(role)));
                } else {
                    state = String.valueOf(next.get("status"));
                    if ("DONE".equals(state) || "FAILED".equals(state)) {
                        break;
                    }
                }
            }

            assertEquals("DONE", service.status().get("state"), "project should finish; reached: " + state);
            assertEquals("class App {}", repo.read("src/App.java").orElseThrow());
        } finally {
            memory.close();
        }
    }
}
