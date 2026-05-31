package com.orchestration.engine;

import com.orchestration.agent.ConfigurableAgentFactory;
import com.orchestration.artifact.JGitArtifactRepository;
import com.orchestration.audit.InMemoryAuditLog;
import com.orchestration.budget.DefaultTokenBudgetManager;
import com.orchestration.config.AgentDefinition;
import com.orchestration.config.AgentsProperties;
import com.orchestration.config.LlmProperties;
import com.orchestration.memory.SqliteMemoryStore;
import com.orchestration.task.WorkflowState;
import com.orchestration.testsupport.ScriptedLlmClient;
import com.orchestration.tools.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full vertical slice with a scripted LLM: Team Lead decomposes → architect designs → developer
 * writes code committed to Git → project completes, with checkpoints persisted to SQLite. No
 * network, key, or Docker required.
 */
class EngineAgentsEndToEndTest {

    @TempDir
    Path repoDir;

    private static AgentsProperties agents() {
        return new AgentsProperties(Map.of(
                "team-lead", AgentDefinition.of("TEAM_LEAD", "opus", null, List.of("DECOMPOSE_TASKS"), 4096, 0.2),
                "backend-architect", AgentDefinition.of("BACKEND_ARCHITECT", "opus", null,
                        List.of("DESIGN_ARCHITECTURE"), 8192, 0.2),
                "backend-developer", AgentDefinition.of("BACKEND_DEVELOPER", "sonnet", null,
                        List.of("WRITE_CODE", "EXECUTE_TOOLS"), 8192, 0.1)));
    }

    private static LlmProperties llmProperties() {
        return new LlmProperties(
                new LlmProperties.Api("http://x", "2023-06-01", "key", 30),
                new LlmProperties.Retry(2, 1, 5, 2.0),
                new LlmProperties.PromptCache(true),
                Map.of("opus", "claude-opus-4-8", "sonnet", "claude-sonnet-4-6"));
    }

    @Test
    void runsAFeatureRequestThroughTheTeamToCommittedCode() throws Exception {
        // Scripted in call order: Team Lead decomposition, then architect, then developer.
        ScriptedLlmClient llm = new ScriptedLlmClient(
                "{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\",\"output\":{\"tasks\":["
                        + "{\"id\":\"a\",\"title\":\"Architecture\",\"description\":\"design\",\"role\":\"BACKEND_ARCHITECT\",\"dependsOn\":[]},"
                        + "{\"id\":\"b\",\"title\":\"Implement\",\"description\":\"code\",\"role\":\"BACKEND_DEVELOPER\",\"dependsOn\":[\"a\"]}]}}",
                "{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\",\"output\":{\"design\":\"layered\"}}",
                "{\"status\":\"COMPLETED\",\"confidence\":\"HIGH\",\"artifacts\":["
                        + "{\"path\":\"src/main/java/App.java\",\"content\":\"class App {}\"},"
                        + "{\"path\":\"src/test/java/AppTest.java\",\"content\":\"class AppTest {}\"}],"
                        + "\"output\":{\"summary\":\"done\"}}");

        ToolExecutor tools = req -> new ToolExecutor.ExecutionResult(0, "", "", false, Duration.ZERO);
        ConfigurableAgentFactory factory = new ConfigurableAgentFactory(
                agents(), llmProperties(), llm, tools, new DefaultTokenBudgetManager(),
                com.orchestration.agent.SkillRegistry.empty());

        SqliteMemoryStore memory = SqliteMemoryStore.inMemory();
        try {
            JGitArtifactRepository repo = new JGitArtifactRepository(repoDir);
            InMemoryAuditLog audit = new InMemoryAuditLog();
            DefaultOrchestrationEngine engine = new DefaultOrchestrationEngine(
                    new TeamLeadProjectPlanner(factory),
                    new AgentTaskProcessor(factory, repo, audit),
                    memory, audit);

            var handle = engine.submit(new OrchestrationEngine.ProjectRequest(
                    "build a todo app", Map.of(), Optional.empty()));

            assertEquals(WorkflowState.DONE, engine.awaitSettled(handle.projectId(), Duration.ofSeconds(10)));
            assertEquals("class App {}", repo.read("src/main/java/App.java").orElseThrow());
            assertFalse(memory.checkpoints(handle.projectId()).isEmpty());
            assertTrue(engine.status(handle.projectId()).completedTasks() >= 2);
        } finally {
            memory.close();
        }
    }
}
