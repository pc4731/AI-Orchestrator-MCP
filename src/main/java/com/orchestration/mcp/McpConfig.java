package com.orchestration.mcp;

import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.SkillRegistry;
import com.orchestration.budget.TokenBudgetManager;
import com.orchestration.config.AgentsProperties;
import com.orchestration.config.FeedbackProperties;
import com.orchestration.config.KnowledgeProperties;
import com.orchestration.config.WorkspaceProperties;
import com.orchestration.engine.ClarificationGateway;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.feedback.FeedbackReporter;
import com.orchestration.knowledge.ProjectKnowledgeStore;
import com.orchestration.audit.AuditLog;
import com.orchestration.learning.LessonStore;
import com.orchestration.memory.MemoryStore;
import com.orchestration.metrics.MetricsStore;
import com.orchestration.verify.HostProjectBuildVerifier;
import com.orchestration.verify.ProjectBuildVerifier;
import com.orchestration.web.ActiveProject;
import com.orchestration.workspace.FileProjectWorkspaces;
import com.orchestration.workspace.ProjectWorkspaces;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    /**
     * Per-project workspaces: each project Claude Code drives gets its own folder under the configured
     * base dir (defaults to the Desktop), with an isolated Git repo and knowledge file — so sequential
     * runs never clobber each other in one shared {@code data/repo}.
     */
    @Bean
    public ProjectWorkspaces projectWorkspaces(WorkspaceProperties workspace, KnowledgeProperties knowledge) {
        return new FileProjectWorkspaces(Path.of(workspace.baseDir()), knowledge.active(), knowledge.path());
    }

    /**
     * Real build verification (mcp profile): runs the project's test command on the host and gates the
     * QA step on the actual exit code, so DONE means the code provably builds. Only registered when
     * {@code workspace.verify-build} is on (the default); turn it off if the host can't run builds.
     */
    @Bean
    @ConditionalOnProperty(name = "workspace.verify-build", havingValue = "true", matchIfMissing = true)
    public ProjectBuildVerifier projectBuildVerifier() {
        return new HostProjectBuildVerifier();
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

    /** Append-only trend log of per-run quality tallies, so rework/build-fix counts can be tracked
     *  across projects (survives restarts). */
    @Bean
    public MetricsStore metricsStore() {
        return new MetricsStore(Path.of("metrics/runs.jsonl"));
    }

    /** Proposals inbox for the learning loop — evidence-backed lessons mined from finished runs,
     *  PENDING until the user approves them via orchestrate_review_lessons. */
    @Bean
    public LessonStore lessonStore() {
        return new LessonStore(Path.of("learning/proposals.jsonl"));
    }

    @Bean
    public OrchestrationMcpService orchestrationMcpService(OrchestrationEngine engine,
                                                          McpBridge bridge,
                                                          MemoryStore memoryStore,
                                                          ActiveProject activeProject,
                                                          ProjectKnowledgeStore knowledgeStore,
                                                          WorkspaceProperties workspace,
                                                          FeedbackReporter feedbackReporter,
                                                          ProjectWorkspaces workspaces,
                                                          AuditLog auditLog,
                                                          MetricsStore metricsStore,
                                                          LessonStore lessonStore,
                                                          SkillRegistry skills) {
        return new OrchestrationMcpService(engine, bridge, memoryStore, activeProject,
                knowledgeStore, workspace.repoDir(), feedbackReporter, workspaces, auditLog, metricsStore,
                lessonStore, skills);
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
