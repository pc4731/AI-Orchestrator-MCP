package com.orchestration.config;

import com.orchestration.agent.SkillRegistry;
import com.orchestration.llm.AnthropicClientConfig;
import com.orchestration.llm.AnthropicLlmClient;
import com.orchestration.llm.LlmClient;
import com.orchestration.tools.SandboxSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Central Spring wiring + configuration-properties registration.
 *
 * <p>This step wires the externally-configured, side-effect-free collaborators: the LLM client and
 * the sandbox settings. The remaining infrastructure beans (memory store, artifact repo, engine,
 * agents) are assembled in later steps as those components come online.
 */
@Configuration
@EnableConfigurationProperties({
        LlmProperties.class,
        AgentsProperties.class,
        BudgetProperties.class,
        MemoryProperties.class,
        MessageBusProperties.class,
        SandboxProperties.class,
        WorkspaceProperties.class,
        KnowledgeProperties.class,
        FeedbackProperties.class,
        SkillsProperties.class
})
public class OrchestrationConfig {

    @Bean
    @Profile("!demo & !ui")
    public LlmClient llmClient(LlmProperties properties) {
        LlmProperties.Api api = properties.api();
        LlmProperties.Retry retry = properties.retry();
        AnthropicClientConfig config = new AnthropicClientConfig(
                api.baseUrl(),
                api.anthropicVersion(),
                api.apiKey(),
                Duration.ofSeconds(api.timeoutSeconds()),
                retry.maxRetries(),
                retry.initialBackoffMillis(),
                retry.maxBackoffMillis(),
                retry.multiplier(),
                properties.promptCache().enabled());
        return AnthropicLlmClient.create(config);
    }

    @Bean
    public SandboxSettings sandboxSettings(SandboxProperties properties) {
        return properties.toSettings();
    }

    @Bean
    public SkillRegistry skillRegistry(SkillsProperties properties) {
        return new SkillRegistry(Path.of(properties.dir()));
    }
}
