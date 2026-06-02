package com.orchestration.engine;

import java.util.List;
import java.util.Optional;

/**
 * The channel through which the planner can <i>pause and involve the human</i> during the pre-build
 * clarification loop: the Business Analyst researches and drafts a spec, asks the user the open
 * questions, folds the answers back in, and repeats until the understanding is confirmed — only then
 * does the Team Lead decompose and the build start.
 *
 * <p>The {@code mcp} profile backs this with Claude Code as the relay (it asks the real user in
 * chat). When no gateway is available (plain unit tests, or the LLM profile with no human present),
 * the planner proceeds without human interaction — exactly the old single-pass behaviour.
 */
public interface ClarificationGateway {

    /**
     * Relay the open questions to the human and block until they answer.
     *
     * @return the human's answers as free text, or empty if no human is available / they declined,
     *         in which case the planner stops looping and proceeds with what it has.
     */
    Optional<String> ask(String projectId, List<String> questions, String contextSummary);

    /** Show the refined understanding to the human and block for their verdict before building. */
    Confirmation confirm(String projectId, String understanding);

    /** Outcome of a confirmation step: approved, or rejected with corrections to fold back in. */
    record Confirmation(boolean confirmed, String corrections) {
        public static Confirmation approved() {
            return new Confirmation(true, "");
        }

        public static Confirmation changesRequested(String corrections) {
            return new Confirmation(false, corrections == null ? "" : corrections);
        }
    }
}
