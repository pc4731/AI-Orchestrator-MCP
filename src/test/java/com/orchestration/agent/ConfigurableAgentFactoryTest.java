package com.orchestration.agent;

import com.orchestration.agent.SkillRegistry;
import com.orchestration.budget.DefaultTokenBudgetManager;
import com.orchestration.config.AgentDefinition;
import com.orchestration.config.AgentsProperties;
import com.orchestration.config.LlmProperties;
import com.orchestration.testsupport.ScriptedLlmClient;
import com.orchestration.tools.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurableAgentFactoryTest {

    private static final ToolExecutor TOOLS =
            req -> new ToolExecutor.ExecutionResult(0, "", "", false, Duration.ZERO);

    private static ConfigurableAgentFactory factory() {
        AgentsProperties agents = new AgentsProperties(Map.of(
                "team-lead", AgentDefinition.of("TEAM_LEAD", "opus", "nonexistent.md",
                        List.of("DECOMPOSE_TASKS"), 4096, 0.2),
                "backend-developer", AgentDefinition.of("BACKEND_DEVELOPER", "sonnet", null,
                        List.of("WRITE_CODE", "EXECUTE_TOOLS"), 8192, 0.1),
                "qa-engineer", AgentDefinition.of("QA_ENGINEER", "sonnet", null,
                        List.of("RUN_TESTS", "EXECUTE_TOOLS"), 4096, 0.2),
                "backend-architect", AgentDefinition.of("BACKEND_ARCHITECT", "opus", null,
                        List.of("DESIGN_ARCHITECTURE"), 8192, 0.2),
                "ai-ml-architect", AgentDefinition.of("AI_ML_ARCHITECT", "opus", null,
                        List.of("DESIGN_ARCHITECTURE"), 8192, 0.2),
                "ai-ml-developer", AgentDefinition.of("AI_ML_DEVELOPER", "sonnet", null,
                        List.of("WRITE_CODE", "EXECUTE_TOOLS"), 8192, 0.1)));
        LlmProperties llm = new LlmProperties(
                new LlmProperties.Api("http://x", "2023-06-01", "key", 30),
                new LlmProperties.Retry(3, 1, 5, 2.0),
                new LlmProperties.PromptCache(true),
                Map.of("opus", "claude-opus-4-8", "sonnet", "claude-sonnet-4-6"));
        return new ConfigurableAgentFactory(agents, llm, new ScriptedLlmClient(), TOOLS,
                new DefaultTokenBudgetManager(), SkillRegistry.empty());
    }

    @Test
    void createsTheBehaviourClassMatchingEachRole() {
        ConfigurableAgentFactory factory = factory();
        assertInstanceOf(TeamLeadAgent.class, factory.create(AgentRole.TEAM_LEAD));
        assertInstanceOf(DeveloperAgent.class, factory.create(AgentRole.BACKEND_DEVELOPER));
        assertInstanceOf(QaEngineerAgent.class, factory.create(AgentRole.QA_ENGINEER));
        assertInstanceOf(GenericLlmAgent.class, factory.create(AgentRole.BACKEND_ARCHITECT));
        assertInstanceOf(GenericLlmAgent.class, factory.create(AgentRole.AI_ML_ARCHITECT));
        assertInstanceOf(DeveloperAgent.class, factory.create(AgentRole.AI_ML_DEVELOPER));
    }

    @Test
    void resolvesModelAliasAndCapabilities() {
        Agent developer = factory().create(AgentRole.BACKEND_DEVELOPER);
        assertEquals(AgentRole.BACKEND_DEVELOPER, developer.role());
        assertTrue(developer.capabilities().contains(Capability.WRITE_CODE));
    }

    @Test
    void reportsSupportedRoles() {
        ConfigurableAgentFactory factory = factory();
        assertTrue(factory.supports(AgentRole.TEAM_LEAD));
        assertFalse(factory.supports(AgentRole.DBA));
        assertTrue(factory.supportedRoles().contains(AgentRole.QA_ENGINEER));
    }

    @Test
    void throwsForUnconfiguredRole() {
        assertThrows(IllegalArgumentException.class, () -> factory().create(AgentRole.DBA));
    }
}
