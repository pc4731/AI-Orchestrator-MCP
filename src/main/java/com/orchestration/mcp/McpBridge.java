package com.orchestration.mcp;

import com.orchestration.agent.Agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The rendezvous between the orchestration engine (which dispatches agent tasks on background
 * threads) and the MCP client — Claude Code — which fulfils them.
 *
 * <p>It inverts control: instead of an agent calling an LLM, the {@code McpAgent}'s {@code handle}
 * <i>parks</i> the task here and blocks; the MCP {@code orchestrate_next} tool hands the parked task
 * to Claude Code, and {@code orchestrate_submit} delivers the result back, unblocking the agent.
 * This lets Claude Code act as every agent with no API calls.
 */
public class McpBridge {

    /** A task waiting for Claude Code to fulfil it. */
    public record PendingTask(
            String taskId,
            String projectId,
            String role,
            String title,
            String description,
            String systemPrompt,
            String instructions,
            String responseSchema,
            Map<String, String> grounding
    ) {
        public PendingTask {
            grounding = grounding == null ? Map.of() : Map.copyOf(grounding);
        }
    }

    private final LinkedBlockingQueue<PendingTask> queue = new LinkedBlockingQueue<>();
    private final Map<String, CompletableFuture<Agent.Response>> awaiting = new ConcurrentHashMap<>();
    private volatile CompletableFuture<String> startRendezvous;

    // ---- engine side (called from McpAgent.handle on background threads) ----

    /** Park a task and block until Claude Code submits its result. */
    public Agent.Response dispatch(PendingTask task) {
        CompletableFuture<Agent.Response> future = new CompletableFuture<>();
        awaiting.put(task.taskId(), future);
        queue.add(task);
        CompletableFuture<String> rendezvous = startRendezvous;
        if (rendezvous != null && !rendezvous.isDone()) {
            rendezvous.complete(task.projectId()); // first dispatch reveals the project id to start()
        }
        return future.join();
    }

    // ---- MCP side (called from the tool service) ----

    /** Arm the one-shot rendezvous used by {@code orchestrate_start} to learn the project id. */
    public void armStart() {
        startRendezvous = new CompletableFuture<>();
    }

    /** Wait for the first task to be parked and return its project id. */
    public String awaitProjectId(long timeoutMillis) throws InterruptedException {
        try {
            return startRendezvous.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** Take the next parked task, waiting up to {@code timeoutMillis} for one to appear. */
    public Optional<PendingTask> poll(long timeoutMillis) {
        try {
            return Optional.ofNullable(queue.poll(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Deliver a result for a parked task. Returns false if the task id is unknown. */
    public boolean complete(String taskId, Agent.Response response) {
        CompletableFuture<Agent.Response> future = awaiting.remove(taskId);
        if (future == null) {
            return false;
        }
        future.complete(response);
        return true;
    }

    /** Task ids currently awaiting a result (parked or in-flight). */
    public List<String> awaitingTaskIds() {
        return List.copyOf(awaiting.keySet());
    }
}
