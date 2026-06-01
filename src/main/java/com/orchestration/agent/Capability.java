package com.orchestration.agent;

/**
 * A discrete capability an agent may possess. Capabilities are how the system scopes tools
 * per agent: a {@code WRITE_CODE} or {@code RUN_TESTS} capability is what grants access to the
 * sandboxed execution tools, while a Team Lead that only plans does not get them.
 *
 * <p>Kept coarse-grained on purpose; finer tool scoping lives in the sandbox configuration.
 */
public enum Capability {

    ELICIT_REQUIREMENTS,
    REFINE_PROMPT,
    CLARIFY_REQUIREMENTS,
    DECOMPOSE_TASKS,
    DESIGN_ARCHITECTURE,
    DESIGN_UI,
    WRITE_CODE,
    REVIEW_CODE,
    RUN_TESTS,
    DESIGN_SCHEMA,
    SECURITY_AUDIT,
    EXECUTE_TOOLS
}
