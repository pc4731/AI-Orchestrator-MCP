package com.orchestration.demo;

import com.orchestration.artifact.ArtifactRepository;
import com.orchestration.audit.AuditLog;
import com.orchestration.engine.OrchestrationEngine;
import com.orchestration.memory.MemoryStore;
import com.orchestration.task.WorkflowState;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs an end-to-end demonstration under the {@code demo} profile: it submits a sample feature
 * request and prints the resulting flow — decomposition, the audit timeline, the code committed to
 * Git, the checkpoints written to SQLite, and a resume that does not repeat completed work.
 *
 * <p>Run with: {@code ./gradlew bootRun --args='--spring.profiles.active=demo'}
 */
@Component
@Profile("demo")
public class DemoRunner implements CommandLineRunner {

    private static final String SAMPLE_REQUEST =
            "Build a small calculator service exposing add and subtract operations.";

    private final OrchestrationEngine engine;
    private final ArtifactRepository artifacts;
    private final AuditLog auditLog;
    private final MemoryStore memoryStore;

    public DemoRunner(OrchestrationEngine engine, ArtifactRepository artifacts,
                      AuditLog auditLog, MemoryStore memoryStore) {
        this.engine = engine;
        this.artifacts = artifacts;
        this.auditLog = auditLog;
        this.memoryStore = memoryStore;
    }

    @Override
    public void run(String... args) throws Exception {
        banner("AI AGENT ORCHESTRATION — END-TO-END DEMO");
        System.out.println("Feature request: " + SAMPLE_REQUEST);

        OrchestrationEngine.ProjectHandle handle = engine.submit(new OrchestrationEngine.ProjectRequest(
                SAMPLE_REQUEST, Map.of(), Optional.of(2_000_000L)));
        String projectId = handle.projectId();

        WorkflowState state = pollUntilSettled(projectId, Duration.ofSeconds(30));
        OrchestrationEngine.ProjectStatus status = engine.status(projectId);

        banner("RESULT");
        System.out.println("Project " + projectId);
        System.out.println("Final state : " + state);
        System.out.println("Tasks       : " + status.completedTasks() + "/" + status.totalTasks() + " completed");
        System.out.println("Open gates  : " + status.pendingGates().size());

        banner("AUDIT TIMELINE");
        auditLog.forProject(projectId).forEach(e ->
                System.out.printf("  %-12s %-9s %s%n", e.actor(), e.type(), e.summary()));

        banner("ARTIFACTS COMMITTED TO GIT");
        List<String> files = artifacts.list("");
        files.forEach(f -> System.out.println("  " + f));
        artifacts.read("src/main/java/demo/Calculator.java").ifPresent(content -> {
            System.out.println("\n  --- src/main/java/demo/Calculator.java ---");
            content.lines().forEach(line -> System.out.println("  " + line));
        });

        banner("CHECKPOINTS (SQLite)");
        System.out.println("  " + memoryStore.checkpoints(projectId).size()
                + " checkpoints written during the run.");

        banner("RESUME (no completed work is repeated)");
        OrchestrationEngine.ProjectHandle resumed = engine.resume(projectId);
        System.out.println("  Resumed project state: " + resumed.state());

        banner("DEMO COMPLETE");
    }

    private WorkflowState pollUntilSettled(String projectId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            WorkflowState state = engine.status(projectId).state();
            if (state == WorkflowState.DONE || state == WorkflowState.FAILED
                    || state == WorkflowState.BLOCKED || state == WorkflowState.NEEDS_CLARIFICATION) {
                return state;
            }
            Thread.sleep(50);
        }
        return engine.status(projectId).state();
    }

    private void banner(String title) {
        System.out.println("\n========== " + title + " ==========");
    }
}
