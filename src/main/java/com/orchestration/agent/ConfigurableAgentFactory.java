package com.orchestration.agent;

import com.orchestration.budget.TokenBudgetManager;
import com.orchestration.config.AgentDefinition;
import com.orchestration.config.AgentsProperties;
import com.orchestration.config.LlmProperties;
import com.orchestration.llm.LlmClient;
import com.orchestration.llm.ModelId;
import com.orchestration.tools.ToolExecutor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds agents from YAML configuration (Factory pattern).
 *
 * <p>For each {@link AgentRole} it looks up the matching {@link AgentDefinition}, resolves the model
 * alias via {@link LlmProperties}, loads the system prompt file (falling back to a built-in default),
 * parses capabilities, and instantiates the agent class appropriate to the role's <i>behaviour</i>.
 * Adding a role means adding a config entry plus, at most, a case here — the engine never changes.
 */
public class ConfigurableAgentFactory implements AgentFactory {

    private static final int DEFAULT_MAX_SCHEMA_RETRIES = 1;
    private static final List<String> DEFAULT_TEST_COMMAND = List.of("./gradlew", "test");

    private final AgentsProperties agents;
    private final LlmProperties llmProperties;
    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private final TokenBudgetManager budget;

    public ConfigurableAgentFactory(AgentsProperties agents,
                                    LlmProperties llmProperties,
                                    LlmClient llmClient,
                                    ToolExecutor toolExecutor,
                                    TokenBudgetManager budget) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.llmProperties = Objects.requireNonNull(llmProperties, "llmProperties");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    @Override
    public Agent create(AgentRole role) {
        AgentDefinition definition = findDefinition(role);
        AgentSpec spec = toSpec(role, definition);
        return switch (role) {
            case TEAM_LEAD -> new TeamLeadAgent(spec, llmClient, budget);
            case BACKEND_DEVELOPER, FRONTEND_DEVELOPER -> new DeveloperAgent(spec, llmClient, budget);
            case QA_ENGINEER -> new QaEngineerAgent(spec.id(), role, spec.capabilities(),
                    toolExecutor, DEFAULT_TEST_COMMAND, Duration.ofMinutes(10));
            default -> new GenericLlmAgent(spec, llmClient, budget);
        };
    }

    @Override
    public boolean supports(AgentRole role) {
        return agents.definitions().values().stream().anyMatch(d -> role.name().equals(d.role()));
    }

    @Override
    public Set<AgentRole> supportedRoles() {
        return agents.definitions().values().stream()
                .map(d -> AgentRole.valueOf(d.role()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private AgentSpec toSpec(AgentRole role, AgentDefinition definition) {
        ModelId model = new ModelId(llmProperties.resolveModel(definition.model()));
        Set<Capability> capabilities = definition.capabilities().stream()
                .map(Capability::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        String prompt = AgentPrompts.load(definition.promptFile(), role);
        return new AgentSpec(AgentId.random(), role, capabilities, model, prompt,
                definition.maxOutputTokens(), definition.temperature(),
                llmProperties.promptCache().enabled(), DEFAULT_MAX_SCHEMA_RETRIES);
    }

    private AgentDefinition findDefinition(AgentRole role) {
        return agents.definitions().values().stream()
                .filter(d -> role.name().equals(d.role()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No agent configured for role " + role));
    }

    /** Helper for callers/tests that build their own request parameter maps. */
    public static Map<String, Object> withWorkingDir(String workingDir) {
        return Map.of("workingDir", workingDir);
    }
}
