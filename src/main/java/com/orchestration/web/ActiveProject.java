package com.orchestration.web;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Thread-safe holder for the id of the project currently being driven (by Claude Code over MCP).
 * It lets the read-only web dashboard discover and follow that project without being the driver.
 *
 * <p>Always present (a plain component); only the {@code mcp} profile actually sets it — in the
 * interactive {@code ui} profile the browser drives projects directly and never reads this.
 */
@Component
public class ActiveProject {

    private volatile String projectId;

    public void set(String projectId) {
        this.projectId = projectId;
    }

    public Optional<String> get() {
        return Optional.ofNullable(projectId);
    }
}
