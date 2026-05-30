package com.orchestration.agent;

import com.orchestration.budget.TokenBudgetManager;
import com.orchestration.llm.LlmClient;

/**
 * The Team Lead / Business Analyst — the Mediator at the agent level.
 *
 * <p>Its distinctive job is decomposition: turning a feature request into a list of concrete,
 * role-assigned tasks (consumed by the {@code TeamLeadProjectPlanner} to build the task graph), or
 * — when the request is too ambiguous — escalating with clarifying questions instead of guessing.
 */
public class TeamLeadAgent extends AbstractAgent {

    public TeamLeadAgent(AgentSpec spec, LlmClient llm, TokenBudgetManager budget) {
        super(spec, llm, budget);
    }

    @Override
    protected String buildUserPrompt(Request request, Context context) {
        return super.buildUserPrompt(request, context) + "\n\n" + """
                As the Team Lead, decompose the request into concrete tasks. Put them in
                output.tasks as a list of objects:
                  {"id":"t1","title":"...","description":"...","role":"BACKEND_ARCHITECT","dependsOn":["t0"]}
                where role is one of TEAM_LEAD, BACKEND_ARCHITECT, FRONTEND_ARCHITECT, UI_DESIGNER,
                BACKEND_DEVELOPER, FRONTEND_DEVELOPER, QA_ENGINEER, DBA, SECURITY_REVIEWER, and
                dependsOn lists the ids of tasks that must finish first.
                If the request is too ambiguous to decompose, set status INSUFFICIENT_INFORMATION
                and list the questions in output.questions.""";
    }
}
