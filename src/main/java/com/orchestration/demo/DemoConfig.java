package com.orchestration.demo;

import com.orchestration.llm.LlmClient;
import com.orchestration.tools.ToolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * Offline beans for the {@code demo} (console) and {@code ui} (web) profiles. They replace the real
 * Anthropic client and the Docker tool executor so both run with no API key, network, or Docker.
 */
@Configuration
@Profile("demo | ui")
public class DemoConfig {

    @Bean
    public LlmClient llmClient() {
        return new DemoLlmClient();
    }

    @Bean
    public ToolExecutor toolExecutor() {
        // Stand in for the sandbox: pretend the QA test run succeeded.
        return request -> new ToolExecutor.ExecutionResult(0, "Demo sandbox: all tests passed", "",
                false, Duration.ZERO);
    }
}
