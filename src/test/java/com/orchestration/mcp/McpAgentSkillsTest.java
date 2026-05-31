package com.orchestration.mcp;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentRole;
import com.orchestration.agent.SkillRegistry;
import com.orchestration.config.AgentDefinition;
import com.orchestration.config.AgentsProperties;
import com.orchestration.task.Task;
import com.orchestration.task.TaskId;
import com.orchestration.task.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves a configured skill flows all the way to the brain: an MCP agent built for a role with a
 * skill carries that skill's text in the task persona handed to Claude Code.
 */
class McpAgentSkillsTest {

    @TempDir
    Path skillsDir;

    @Test
    void configuredSkillAppearsInTheTaskPersona() throws Exception {
        Files.writeString(skillsDir.resolve("accessibility.md"), "WCAG_AA_GUIDANCE");
        AgentsProperties agents = new AgentsProperties(Map.of(
                "ui-designer", new AgentDefinition("UI_DESIGNER", "x", null,
                        List.of("DESIGN_UI"), 4096, 0.4, List.of("accessibility"))));

        McpBridge bridge = new McpBridge();
        McpAgentFactory factory = new McpAgentFactory(agents, bridge, new SkillRegistry(skillsDir));
        Agent designer = factory.create(AgentRole.UI_DESIGNER);

        Task task = new Task(new TaskId("t1"), "Design", "d", AgentRole.UI_DESIGNER,
                WorkflowState.PENDING, List.of(), Map.of(), Instant.now(), Instant.now());
        // handle() blocks until the task is completed via the bridge; run it async and inspect the parked task.
        CompletableFuture.runAsync(() -> designer.handle(
                new Agent.Request(task, "design it", Map.of(), Map.of()),
                new Agent.Context("p1", "t1", Map.of())));

        Optional<McpBridge.PendingTask> parked = bridge.poll(2000);
        assertTrue(parked.isPresent(), "task should be parked for the brain");
        assertTrue(parked.get().systemPrompt().contains("WCAG_AA_GUIDANCE"),
                "the configured skill text should be in the persona");

        bridge.complete("t1", new Agent.Response(Agent.Outcome.COMPLETED, Map.of(), List.of(),
                Agent.Confidence.HIGH, List.of(), Optional.empty())); // release the async handler
    }
}
