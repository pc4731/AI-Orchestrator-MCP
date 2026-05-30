package com.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.orchestration.budget.TokenBudgetManager;
import com.orchestration.llm.LlmClient;

import java.util.Optional;

/**
 * Backend or frontend developer: implements code from the architect's spec, emitting each file as
 * an artifact (path + content) for the orchestrator to commit to the Git-backed repository.
 *
 * <p>Adds one grounding guard on top of the base flow: a developer that claims it completed but
 * produced no code is downgraded to {@code NEEDS_REVIEW} — completion must be backed by real files,
 * not just a confident summary.
 */
public class DeveloperAgent extends AbstractAgent {

    public DeveloperAgent(AgentSpec spec, LlmClient llm, TokenBudgetManager budget) {
        super(spec, llm, budget);
    }

    @Override
    protected String buildUserPrompt(Request request, Context context) {
        return super.buildUserPrompt(request, context) + "\n\n" + """
                As the developer, implement the required code. Return every file you create or change
                in the artifacts array, each with its repository-relative path and full content.
                Summarise what you built in output.summary. Use only declared, verifiable
                dependencies; flag anything uncertain instead of inventing APIs.""";
    }

    @Override
    protected Response afterParse(JsonNode node, Response base, Request request, Context context) {
        if (base.outcome() == Outcome.COMPLETED && base.artifacts().isEmpty()) {
            return new Response(Outcome.NEEDS_REVIEW, base.structuredOutput(), base.artifacts(),
                    base.confidence(), base.assumptions(),
                    Optional.of("Marked complete but produced no code artifacts"));
        }
        return base;
    }
}
