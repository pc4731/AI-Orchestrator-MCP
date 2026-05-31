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
                As the developer, implement the required code AND its automated tests. Return every
                file you create or change in the artifacts array, each with its repository-relative
                path and full content — including test files that cover each feature you implement
                (happy path plus key edge cases), runnable by the project's standard test command.
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
        // Completion must include tests: if nothing looks like a test file, send back for review.
        if (base.outcome() == Outcome.COMPLETED && !base.artifacts().isEmpty() && !hasTestArtifact(base)) {
            return new Response(Outcome.NEEDS_REVIEW, base.structuredOutput(), base.artifacts(),
                    base.confidence(), base.assumptions(),
                    Optional.of("No test files were produced; implementation must include tests"));
        }
        return base;
    }

    /** Heuristic: does any artifact path look like a test file? */
    private boolean hasTestArtifact(Response response) {
        return response.artifacts().stream().anyMatch(a -> {
            String p = a.path().toLowerCase();
            return p.contains("/test/") || p.contains("test") || p.contains("spec.")
                    || p.endsWith("_test.py") || p.endsWith(".test.js") || p.endsWith(".test.ts");
        });
    }
}
