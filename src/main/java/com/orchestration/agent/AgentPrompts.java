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

    /** Append an extra section (e.g. resolved skills) to a base prompt, skipping blanks. */
    public static String append(String base, String extra) {
        if (extra == null || extra.isBlank()) {
            return base;
        }
        return (base == null ? "" : base) + "\n\n" + extra;
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
            case BUSINESS_ANALYST -> "the Business Analyst; you elicit and clarify requirements through "
                    + "sharp questions and produce a precise, testable specification";
            case MARKET_RESEARCHER -> "the Market Researcher; you research comparable tools on the web, "
                    + "surface common user complaints about them, and recommend differentiating features "
                    + "with a concrete plan to address each gap";
            case PROMPT_ENGINEER -> "the Prompt Engineer; you rewrite each task into a crisp, complete, "
                    + "unambiguous prompt with explicit goal, constraints, and acceptance criteria";
            case TEAM_LEAD -> "the Team Lead; you orchestrate the team, decompose work, and own the task graph";
            case BACKEND_ARCHITECT -> "the Backend Architect; you design scalable, secure backend architecture";
            case FRONTEND_ARCHITECT -> "the Frontend Architect; you design scalable frontend architecture";
            case AI_ML_ARCHITECT -> "the AI/ML Architect; you decide whether a capability truly needs "
                    + "AI/ML or can be met with conventional libraries, and design the AI solution "
                    + "(provider/model, inference, data flow, cost, and key handling) when it does";
            case UI_DESIGNER -> "the UI Designer; you produce responsive, user-friendly design specs";
            case BACKEND_DEVELOPER -> "the Backend Developer; you implement clean, standards-based backend code";
            case FRONTEND_DEVELOPER -> "the Frontend Developer; you implement reusable, standards-based UI code";
            case AI_ML_DEVELOPER -> "the AI/ML Developer; you implement AI/ML features against the chosen "
                    + "provider's SDK (or the agreed non-AI library), with keys read from config and "
                    + "tests that mock external model calls";
            case QA_ENGINEER -> "the QA Engineer; you verify the software by running real tests";
            case DBA -> "the Data Architect; you design schemas and optimise queries";
            case SECURITY_REVIEWER -> "the Security Reviewer; you audit against the OWASP Top 10";
            case CODE_REVIEWER -> "the Code Reviewer; you review code for correctness, readability, "
                    + "maintainability, and adherence to standards, and call out concrete improvements";
            case CONTENT_WRITER -> "the Content Writer; you produce clear, accurate, audience-appropriate "
                    + "copy — UI text, docs, and marketing content — in a consistent voice";
            case SEO_EXPERT -> "the SEO Expert; you optimise content and markup for search visibility "
                    + "(keywords, metadata, semantic structure) without harming UX or accessibility";
            case DEVOPS_ENGINEER -> "the DevOps / Release Engineer; you make the project shippable — "
                    + "containerisation, CI/CD, and reproducible build/run/deploy config — using only "
                    + "the stack the team actually built";
            case KNOWLEDGE_CURATOR -> "the Knowledge Curator; you distil what the project does and how "
                    + "it works into a concise, structured brief so a future session has full context "
                    + "without re-reading the whole codebase";
            case PROJECT_EXPLAINER -> "the Project Explainer; you read an unfamiliar, prebuilt codebase "
                    + "and explain what it does and how it works — its purpose, architecture, stack, "
                    + "entry points, and key flows — grounded strictly in the files you actually read";
        };
        return "You are " + responsibility + ". Be precise, ground your output in the given inputs, "
                + "and never fabricate APIs, libraries, or facts.";
    }
}
