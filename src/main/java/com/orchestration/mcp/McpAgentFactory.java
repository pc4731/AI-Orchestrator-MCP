package com.orchestration.mcp;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentFactory;
import com.orchestration.agent.AgentId;
import com.orchestration.agent.AgentPrompts;
import com.orchestration.agent.AgentRole;
import com.orchestration.agent.Capability;
import com.orchestration.agent.SkillRegistry;
import com.orchestration.budget.TokenBudgetManager;
import com.orchestration.config.AgentDefinition;
import com.orchestration.config.AgentsProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds {@link McpAgent}s for every role (Factory pattern), so in the {@code mcp} profile Claude
 * Code is the brain behind all agents. Personas/capabilities come from the same {@code agents.yml}
 * configuration used by the LLM factory; roles missing from config still get a sensible default.
 */
public class McpAgentFactory implements AgentFactory {

    private final AgentsProperties agents;
    private final McpBridge bridge;
    private final SkillRegistry skills;
    private final TokenBudgetManager budget; // optional; null disables MCP usage metering

    public McpAgentFactory(AgentsProperties agents, McpBridge bridge, SkillRegistry skills) {
        this(agents, bridge, skills, null);
    }

    public McpAgentFactory(AgentsProperties agents, McpBridge bridge, SkillRegistry skills,
                           TokenBudgetManager budget) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.budget = budget;
    }

    @Override
    public Agent create(AgentRole role) {
        Optional<AgentDefinition> definition = findDefinition(role);
        Set<Capability> capabilities = definition
                .map(d -> d.capabilities().stream().map(Capability::valueOf).collect(Collectors.toUnmodifiableSet()))
                .orElse(Set.of());
        List<String> skillNames = definition.map(AgentDefinition::skills).orElse(List.of());
        String prompt = AgentPrompts.append(
                AgentPrompts.load(definition.map(AgentDefinition::promptFile).orElse(null), role),
                skills.resolve(skillNames));
        return new McpAgent(AgentId.random(), role, capabilities, prompt, bridge, budget);
    }

    @Override
    public boolean supports(AgentRole role) {
        return true; // Claude Code can act as any role
    }

    @Override
    public Set<AgentRole> supportedRoles() {
        return Arrays.stream(AgentRole.values()).collect(Collectors.toUnmodifiableSet());
    }

    private Optional<AgentDefinition> findDefinition(AgentRole role) {
        return agents.definitions().values().stream()
                .filter(d -> role.name().equals(d.role()))
                .findFirst();
    }
}
