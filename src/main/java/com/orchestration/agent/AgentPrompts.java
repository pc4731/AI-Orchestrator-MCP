package com.orchestration.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads agent system prompts from the externalised {@code prompts/} files, with a built-in default
 * per role as a fallback. Shared by the LLM-backed {@link ConfigurableAgentFactory} and the
 * MCP-backed agent factory so both present the same persona to whatever brain executes the task.
 */
public final class AgentPrompts {

    private AgentPrompts() {
    }

    /** Read the configured prompt file, falling back to {@link #defaultPrompt(AgentRole)}. */
    public static String load(String promptFile, AgentRole role) {
        if (promptFile != null && !promptFile.isBlank()) {
            Path path = Path.of(promptFile);
            if (Files.isRegularFile(path)) {
                try {
                    return Files.readString(path);
                } catch (IOException e) {
                    // fall through to the built-in default
                }
            }
        }
        return defaultPrompt(role);
    }

    public static String defaultPrompt(AgentRole role) {
        String responsibility = switch (role) {
            case TEAM_LEAD -> "the Team Lead and Business Analyst; you decompose work and own the task graph";
            case BACKEND_ARCHITECT -> "the Backend Architect; you design scalable, secure backend architecture";
            case FRONTEND_ARCHITECT -> "the Frontend Architect; you design scalable frontend architecture";
            case UI_DESIGNER -> "the UI Designer; you produce responsive, user-friendly design specs";
            case BACKEND_DEVELOPER -> "the Backend Developer; you implement clean, standards-based backend code";
            case FRONTEND_DEVELOPER -> "the Frontend Developer; you implement reusable, standards-based UI code";
            case QA_ENGINEER -> "the QA Engineer; you verify the software by running real tests";
            case DBA -> "the Data Architect; you design schemas and optimise queries";
            case SECURITY_REVIEWER -> "the Security Reviewer; you audit against the OWASP Top 10";
        };
        return "You are " + responsibility + ". Be precise, ground your output in the given inputs, "
                + "and never fabricate APIs, libraries, or facts.";
    }
}
