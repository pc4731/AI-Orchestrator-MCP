package com.orchestration.mcp;

import com.orchestration.agent.AgentFactory;
import com.orchestration.config.AgentsProperties;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.memory.MemoryStore;
import com.orchestration.web.ActiveProject;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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
    public AgentFactory agentFactory(AgentsProperties agents, McpBridge bridge) {
        return new McpAgentFactory(agents, bridge);
    }

    @Bean
    public OrchestrationMcpService orchestrationMcpService(OrchestrationEngine engine,
                                                          McpBridge bridge,
                                                          MemoryStore memoryStore,
                                                          ActiveProject activeProject) {
        return new OrchestrationMcpService(engine, bridge, memoryStore, activeProject);
    }

    @Bean
    public CommandLineRunner mcpServerRunner(OrchestrationMcpService service) {
        // Run the stdio MCP loop on a daemon thread so it doesn't block the web server (the
        // dashboard) that also runs in this process under the mcp profile.
        return args -> {
            Thread t = new Thread(() -> new JsonRpcMcpServer(service).serve(System.in, System.out),
                    "mcp-stdio");
            t.setDaemon(true);
            t.start();
        };
    }
}
