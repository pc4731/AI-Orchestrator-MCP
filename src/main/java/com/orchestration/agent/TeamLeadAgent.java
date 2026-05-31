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
                As the Team Lead, FIRST decide whether the request is specified well enough to build.
                Check for unstated but likely-wanted scope (extra features), whether there is a UI and
                its theme/branding, data/persistence needs (e.g. history retention), and non-functional
                needs (scale, auth, security). If important dimensions are missing or ambiguous, set
                status INSUFFICIENT_INFORMATION and put concise, specific questions in output.questions
                — do NOT guess.

                Only when the request is clear (or the user said to use your best judgment), decompose
                it into concrete tasks in output.tasks as a list of objects:
                  {"id":"t1","title":"...","description":"...","role":"BACKEND_ARCHITECT","dependsOn":["t0"]}
                where role is one of TEAM_LEAD, BACKEND_ARCHITECT, FRONTEND_ARCHITECT, UI_DESIGNER,
                BACKEND_DEVELOPER, FRONTEND_DEVELOPER, QA_ENGINEER, DBA, SECURITY_REVIEWER, and
                dependsOn lists the ids of tasks that must finish first. Cover the full agreed scope
                (include a UI_DESIGNER task when there is a UI, a DBA task for meaningful data, and a
                SECURITY_REVIEWER task when handling user data). Record confirmed decisions in
                output.assumptions.""";
    }
}
