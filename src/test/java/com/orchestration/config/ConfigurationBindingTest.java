package com.orchestration.config;

import com.orchestration.llm.LlmClient;
import com.orchestration.tools.SandboxSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the externalised {@code config/*.yml} files bind through the {@code @ConfigurationProperties}
 * classes (loaded via the {@code spring.config.import} chain in {@code application.yml}) and that the
 * derived beans are produced.
 */
class ConfigurationBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(OrchestrationConfig.class);

    @Test
    void llmPropertiesBindIncludingModelAliases() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            LlmProperties llm = context.getBean(LlmProperties.class);
            assertThat(llm.api().baseUrl()).isEqualTo("https://api.anthropic.com");
            assertThat(llm.api().anthropicVersion()).isEqualTo("2023-06-01");
            assertThat(llm.resolveModel("opus")).isEqualTo("claude-opus-4-8");
            assertThat(llm.resolveModel("sonnet")).isEqualTo("claude-sonnet-4-6");
            assertThat(llm.promptCache().enabled()).isTrue();
        });
    }

    @Test
    void agentDefinitionsBind() {
        runner.run(context -> {
            AgentsProperties agents = context.getBean(AgentsProperties.class);
            assertThat(agents.definitions()).containsKey("team-lead");
            AgentDefinition teamLead = agents.definitions().get("team-lead");
            assertThat(teamLead.role()).isEqualTo("TEAM_LEAD");
            assertThat(teamLead.model()).isEqualTo("opus");
            assertThat(teamLead.capabilities()).contains("DECOMPOSE_TASKS");
        });
    }

    @Test
    void budgetAndSandboxBindAndDerivedBeansAreCreated() {
        runner.run(context -> {
            BudgetProperties budgets = context.getBean(BudgetProperties.class);
            assertThat(budgets.bugLoop().maxRetries()).isEqualTo(3);
            assertThat(budgets.onBreach()).isEqualTo(BudgetProperties.BreachAction.ESCALATE);

            SandboxSettings sandbox = context.getBean(SandboxSettings.class);
            assertThat(sandbox.network()).isEqualTo("none");
            assertThat(sandbox.allowedCommands()).contains("gradle");

            // The LLM client bean is constructed without contacting the API.
            assertThat(context.getBean(LlmClient.class)).isNotNull();
        });
    }
}
