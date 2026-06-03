package com.orchestration.mcp;

import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.SkillRegistry;
import com.orchestration.budget.TokenBudgetManager;
import com.orchestration.config.AgentsProperties;
import com.orchestration.config.FeedbackProperties;
import com.orchestration.config.WorkspaceProperties;
import com.orchestration.engine.ClarificationGateway;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.feedback.FeedbackReporter;
import com.orchestration.knowledge.ProjectKnowledgeStore;
import com.orchestration.memory.MemoryStore;
import com.orchestration.web.ActiveProject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.file.Path;

/**
 * Wiring for the {@code mcp} profile: Claude Code is the brain for every agent (no API key). The
 * {@link McpAgentFactory} supplies the {@code AgentFactory} the rest of the engine depends on, and
 * the {@link CommandLineRunner} runs the stdio MCP server, keeping the process alive.
 */
@Configuration
@Profile("mcp")
public class McpConfig {

    @Bean
    public McpBridge mcpBridge() {
        return new McpBridge();
    }

    @Bean
    public AgentFactory agentFactory(AgentsProperties agents, McpBridge bridge, SkillRegistry skills,
                                     TokenBudgetManager tokenBudgetManager) {
        return new McpAgentFactory(agents, bridge, skills, tokenBudgetManager);
    }

    /**
     * Backs the planner's pre-build clarification loop with Claude Code as the human relay, so the
     * Business Analyst can ask the real user questions and confirm its understanding before any code
     * is written. Only the mcp profile defines this; other profiles run the planner without it.
     */
    @Bean
    public ClarificationGateway clarificationGateway(McpBridge bridge) {
        return new McpClarificationGateway(bridge);
    }

    @Bean
    public FeedbackReporter feedbackReporter(ObjectProvider<JavaMailSender> mailSender,
                                             FeedbackProperties feedback) {
        // JavaMailSender exists only when spring.mail.host is configured; otherwise feedback is
        // written to the backlog file instead of emailed.
        return new FeedbackReporter(mailSender.getIfAvailable(), feedback.active(),
                feedback.to(), feedback.from(), Path.of(feedback.backlogFile()));
    }

    @Bean
    public OrchestrationMcpService orchestrationMcpService(OrchestrationEngine engine,
                                                          McpBridge bridge,
                                                          MemoryStore memoryStore,
                                                          ActiveProject activeProject,
                                                          ProjectKnowledgeStore knowledgeStore,
                                                          WorkspaceProperties workspace,
                                                          FeedbackReporter feedbackReporter) {
        return new OrchestrationMcpService(engine, bridge, memoryStore, activeProject,
                knowledgeStore, workspace.repoDir(), feedbackReporter);
    }

    @Bean
    public CommandLineRunner mcpServerRunner(OrchestrationMcpService service) {
        // Run the stdio MCP loop on a daemon thread so it doesn't block the web server (the
        // dashboard) that also runs in this process under the mcp profile.
        // MCP_DISABLE_RUNNER lets context-startup tests boot the wiring without consuming stdin.
        return args -> {
            if (Boolean.getBoolean("MCP_DISABLE_RUNNER")) {
                return;
            }
            Thread t = new Thread(() -> new JsonRpcMcpServer(service).serve(System.in, System.out),
                    "mcp-stdio");
            t.setDaemon(true);
            t.start();
        };
    }
}
