package com.orchestration.engine;

import com.orchestration.task.TaskGraph;

/**
 * Builds the initial {@link TaskGraph} for a submitted project.
 *
 * <p>In the finished system this is driven by the Team Lead agent decomposing the feature request
 * (added in a later step). Defining it as a seam now lets the {@code OrchestrationEngine}'s dispatch
 * loop be built and unit-tested without depending on agents or the LLM client.
 */
@FunctionalInterface
public interface ProjectPlanner {

    TaskGraph plan(String projectId, OrchestrationEngine.ProjectRequest request);
}
