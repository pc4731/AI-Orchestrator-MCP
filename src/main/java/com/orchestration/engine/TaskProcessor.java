package com.orchestration.engine;

import com.orchestration.agent.Agent;
import com.orchestration.task.Task;

/**
 * Executes a single {@link Task} by routing it to the appropriate agent and returning that agent's
 * structured {@link Agent.Response}.
 *
 * <p>The real, {@code AgentFactory}-backed implementation (which also persists artifacts and meters
 * tokens) arrives in a later step. Keeping it behind this seam decouples the engine's dispatch loop
 * from the agents, so the loop, state handling and checkpointing can be implemented and tested now.
 */
@FunctionalInterface
public interface TaskProcessor {

    Agent.Response process(String projectId, Task task);
}
