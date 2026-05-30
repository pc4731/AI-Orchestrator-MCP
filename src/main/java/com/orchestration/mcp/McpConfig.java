package com.orchestration.mcp;

import com.orchestration.agent.AgentFactory;
import com.orchestration.config.AgentsProperties;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.memory.MemoryStore;
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
                                                          MemoryStore memoryStore) {
        return new OrchestrationMcpService(engine, bridge, memoryStore);
    }

    @Bean
    public CommandLineRunner mcpServerRunner(OrchestrationMcpService service) {
        return args -> new JsonRpcMcpServer(service).serve(System.in, System.out);
    }
}
