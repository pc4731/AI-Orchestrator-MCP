package com.orchestration.agent;

/**
 * The specialised roles that make up the AI software-development team.
 *
 * <p>The role is the key that ties together a task's required skill set, the per-agent YAML
 * configuration (model, system prompt, granted tools), and the {@code AgentFactory} that
 * instantiates the matching agent. Adding a new role here plus a config entry is the extension
 * path for a new agent — the engine itself never needs to change (Open/Closed).
 */
public enum AgentRole {

    BUSINESS_ANALYST,
    MARKET_RESEARCHER,
    PROMPT_ENGINEER,
    TEAM_LEAD,
    BACKEND_ARCHITECT,
    FRONTEND_ARCHITECT,
    AI_ML_ARCHITECT,
    UI_DESIGNER,
    BACKEND_DEVELOPER,
    FRONTEND_DEVELOPER,
    AI_ML_DEVELOPER,
    QA_ENGINEER,
    DBA,
    SECURITY_REVIEWER,
    CODE_REVIEWER,
    CONTENT_WRITER,
    SEO_EXPERT,
    DEVOPS_ENGINEER,
    KNOWLEDGE_CURATOR,
    PROJECT_EXPLAINER
}
