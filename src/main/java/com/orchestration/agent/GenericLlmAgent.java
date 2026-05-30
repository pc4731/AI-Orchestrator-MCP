package com.orchestration.agent;

import com.orchestration.budget.TokenBudgetManager;
import com.orchestration.llm.LlmClient;

/**
 * Config-driven LLM agent that uses the {@link AbstractAgent} flow unchanged.
 *
 * <p>Covers the "advisory" roles that produce structured analysis/design/review output without
 * special handling: backend/frontend architects, UI designer, DBA, and the security reviewer.
 * A new advisory role needs only a config entry — no new class — which is the Open/Closed payoff
 * of organising agents by behaviour rather than one class per role.
 */
public class GenericLlmAgent extends AbstractAgent {

    public GenericLlmAgent(AgentSpec spec, LlmClient llm, TokenBudgetManager budget) {
        super(spec, llm, budget);
    }
}
