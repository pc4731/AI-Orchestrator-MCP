package com.orchestration.config;

import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.ConfigurableAgentFactory;
import com.orchestration.agent.SkillRegistry;
import com.orchestration.artifact.ArtifactRepository;
import com.orchestration.artifact.JGitArtifactRepository;
import com.orchestration.audit.AuditEventBroadcaster;
import com.orchestration.audit.AuditLog;
import com.orchestration.audit.BroadcastingAuditLog;
import com.orchestration.audit.InMemoryAuditLog;
import com.orchestration.budget.DefaultTokenBudgetManager;
import com.orchestration.budget.TokenBudgetManager;
import com.orchestration.bus.InMemoryMessageBus;
import com.orchestration.bus.MessageBus;
import com.orchestration.engine.AgentTaskProcessor;
import com.orchestration.engine.ClarificationGateway;
import com.orchestration.engine.DefaultOrchestrationEngine;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.engine.ProjectPlanner;
import com.orchestration.engine.TaskProcessor;
import com.orchestration.engine.TeamLeadProjectPlanner;
import com.orchestration.knowledge.ProjectKnowledgeStore;
import com.orchestration.llm.LlmClient;
import com.orchestration.memory.MemoryStore;
import com.orchestration.memory.NoOpSemanticMemory;
import com.orchestration.memory.SemanticMemory;
import com.orchestration.memory.SqliteMemoryStore;
import com.orchestration.tools.DockerToolExecutor;
import com.orchestration.tools.SandboxSettings;
import com.orchestration.tools.ToolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Assembles the full runtime object graph from the configured collaborators: message bus, memory
 * store, semantic memory, artifact repository, sandboxed tool executor, budget manager, audit log,
 * the agent factory, and the orchestration engine wired to the Team-Lead planner and agent
 * task processor.
 *
 * <p>Kept separate from {@link OrchestrationConfig} (which only binds properties and the
 * side-effect-free LLM/sandbox beans) so configuration-binding tests don't instantiate these
 * filesystem-backed beans.
 */
@Configuration
public class InfrastructureConfig {

    @Bean
    public MessageBus messageBus() {
        return new InMemoryMessageBus();
    }

    @Bean(destroyMethod = "close")
    public MemoryStore memoryStore(MemoryProperties properties) {
        Path path = Path.of(properties.sqlite().path());
        if (path.getParent() != null) {
            try {
                Files.createDirectories(path.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create memory store directory", e);
            }
        }
        return SqliteMemoryStore.forFile(path);
    }

    @Bean
    public SemanticMemory semanticMemory() {
        return new NoOpSemanticMemory();
    }

    @Bean
    public ArtifactRepository artifactRepository(WorkspaceProperties properties) {
        return new JGitArtifactRepository(Path.of(properties.repoDir()));
    }

    @Bean
    public ProjectKnowledgeStore projectKnowledgeStore(ArtifactRepository artifactRepository,
                                                       KnowledgeProperties knowledge) {
        return new ProjectKnowledgeStore(artifactRepository, knowledge.active(), knowledge.path());
    }

    @Bean
    @Profile("!demo & !ui")
    public ToolExecutor toolExecutor(SandboxSettings sandboxSettings) {
        return new DockerToolExecutor(sandboxSettings);
    }

    @Bean
    public TokenBudgetManager tokenBudgetManager() {
        return new DefaultTokenBudgetManager();
    }

    @Bean
    public AuditEventBroadcaster auditEventBroadcaster() {
        return new AuditEventBroadcaster();
    }

    @Bean
    public AuditLog auditLog(AuditEventBroadcaster broadcaster) {
        // Persist to memory and fan out live to the UI.
        return new BroadcastingAuditLog(new InMemoryAuditLog(), broadcaster);
    }

    @Bean
    @Profile("!mcp")
    public AgentFactory agentFactory(AgentsProperties agents,
                                     LlmProperties llmProperties,
                                     LlmClient llmClient,
                                     ToolExecutor toolExecutor,
                                     TokenBudgetManager tokenBudgetManager,
                                     SkillRegistry skillRegistry) {
        return new ConfigurableAgentFactory(agents, llmProperties, llmClient, toolExecutor,
                tokenBudgetManager, skillRegistry);
    }

    @Bean
    public ProjectPlanner projectPlanner(AgentFactory agentFactory, AuditLog auditLog,
                                         ObjectProvider<ClarificationGateway> clarificationGateway,
                                         ProjectKnowledgeStore knowledgeStore) {
        // The gateway is only defined under the mcp profile (Claude Code relays to the human); when
        // absent the planner runs its single-pass behaviour with no human in the loop. The knowledge
        // store gives the team prior context on edits (inert when the feature is disabled).
        return new TeamLeadProjectPlanner(agentFactory, auditLog,
                clarificationGateway.getIfAvailable(), knowledgeStore);
    }

    @Bean
    public TaskProcessor taskProcessor(AgentFactory agentFactory,
                                       ArtifactRepository artifactRepository,
                                       AuditLog auditLog,
                                       WorkspaceProperties workspace,
                                       BudgetProperties budgets,
                                       ProjectKnowledgeStore knowledgeStore,
                                       ObjectProvider<ClarificationGateway> clarificationGateway) {
        int maxRework = budgets.bugLoop() != null ? budgets.bugLoop().maxRetries() : 2;
        // The gateway is only defined under the mcp profile; when absent, a blocked worker keeps the
        // engine's existing escalation behaviour.
        return new AgentTaskProcessor(agentFactory, artifactRepository, auditLog,
                workspace.repoDir(), workspace.testCommand(), maxRework, knowledgeStore,
                clarificationGateway.getIfAvailable());
    }

    @Bean(destroyMethod = "shutdown")
    public OrchestrationEngine orchestrationEngine(ProjectPlanner projectPlanner,
                                                   TaskProcessor taskProcessor,
                                                   MemoryStore memoryStore,
                                                   AuditLog auditLog,
                                                   TokenBudgetManager tokenBudgetManager,
                                                   BudgetProperties budgets) {
        long defaultProjectBudget = budgets.project() != null && budgets.project().maxTokens() > 0
                ? budgets.project().maxTokens() : Long.MAX_VALUE;
        return new DefaultOrchestrationEngine(projectPlanner, taskProcessor, memoryStore, auditLog,
                tokenBudgetManager, defaultProjectBudget);
    }
}
