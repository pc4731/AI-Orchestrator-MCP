package com.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full Spring context per profile and asserts it starts with the expected beans. Plain
 * unit tests construct beans by hand and never exercise the wiring, so they miss missing-bean /
 * dependency mistakes (e.g. a configuration bean that was never registered). These smoke tests
 * catch exactly that — this very test would have failed on the missing SkillRegistry bean.
 *
 * <p>Random web port (0) avoids colliding with anything already on 8080; an in-memory SQLite store
 * avoids touching the real data dir; {@code MCP_DISABLE_RUNNER} skips the blocking stdio loop.
 */
class SpringContextStartupTest {

    private SpringApplication app(String profile) {
        SpringApplication app = new SpringApplication(OrchestrationApplication.class);
        app.setAdditionalProfiles(profile);
        app.setDefaultProperties(Map.of("memory.sqlite.path", ":memory:"));
        return app;
    }

    // Command-line args outrank profile yml; default properties do not. The mcp profile pins
    // server.port=8090, so forcing the random port (0) here is the only way to actually avoid
    // colliding with anything already bound to that port.
    private static final String[] RANDOM_PORT = {"--server.port=0"};

    @Test
    void mcpProfileContextStartsWithAllAgentBeans() {
        System.setProperty("MCP_DISABLE_RUNNER", "true");
        try (ConfigurableApplicationContext ctx = app("mcp").run(RANDOM_PORT)) {
            assertTrue(ctx.isActive());
            assertTrue(ctx.containsBean("agentFactory"));
            assertTrue(ctx.containsBean("skillRegistry"));
            assertTrue(ctx.containsBean("orchestrationEngine"));
        } finally {
            System.clearProperty("MCP_DISABLE_RUNNER");
        }
    }

    @Test
    void uiProfileContextStartsWithWebBeans() {
        try (ConfigurableApplicationContext ctx = app("ui").run(RANDOM_PORT)) {
            assertTrue(ctx.isActive());
            assertTrue(ctx.containsBean("projectController"));
            assertTrue(ctx.containsBean("skillRegistry"));
        }
    }
}
