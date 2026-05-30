package com.orchestration.agent;

import java.util.Set;

/**
 * Creates {@link Agent} instances for a given {@link AgentRole} (the <b>Factory pattern</b>).
 *
 * <p>The engine asks the factory for an agent by role and never news up a concrete agent itself,
 * so adding a new agent means adding a factory binding plus config — the engine stays closed for
 * modification (Open/Closed).
 */
public interface AgentFactory {

    /**
     * Build an agent for the role, wiring in its configured model, system prompt and granted tools.
     *
     * @throws IllegalArgumentException if the role is not supported
     */
    Agent create(AgentRole role);

    boolean supports(AgentRole role);

    Set<AgentRole> supportedRoles();
}
