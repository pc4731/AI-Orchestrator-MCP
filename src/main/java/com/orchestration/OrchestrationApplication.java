package com.orchestration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the AI agent orchestration layer.
 *
 * <p>This is the wiring root only — it boots the application context that will, in later phases,
 * assemble the orchestration engine, agents, message bus, memory store, LLM client and the
 * externalised configuration. No orchestration logic lives here.
 */
@SpringBootApplication
public class OrchestrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestrationApplication.class, args);
    }
}
