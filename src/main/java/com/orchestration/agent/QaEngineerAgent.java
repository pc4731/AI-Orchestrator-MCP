package com.orchestration.agent;

import com.orchestration.task.Task;
import com.orchestration.tools.ToolExecutor;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * QA Engineer. Unlike the LLM agents, it is grounded in <b>real execution</b>: it runs the project's
 * tests through the sandboxed {@link ToolExecutor} and reports the actual result. This is the
 * anti-hallucination payoff — a passing build is verified, not asserted — so QA does not need the
 * LLM to decide whether the code works.
 */
public class QaEngineerAgent implements Agent {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    private final AgentId id;
    private final AgentRole role;
    private final Set<Capability> capabilities;
    private final ToolExecutor toolExecutor;
    private final List<String> defaultTestCommand;
    private final Duration timeout;

    public QaEngineerAgent(AgentId id, AgentRole role, Set<Capability> capabilities,
                           ToolExecutor toolExecutor, List<String> defaultTestCommand, Duration timeout) {
        this.id = Objects.requireNonNull(id, "id");
        this.role = Objects.requireNonNull(role, "role");
        this.capabilities = Set.copyOf(capabilities);
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.defaultTestCommand = List.copyOf(defaultTestCommand);
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(10);
    }

    @Override
    public AgentId id() {
        return id;
    }

    @Override
    public AgentRole role() {
        return role;
    }

    @Override
    public Set<Capability> capabilities() {
        return capabilities;
    }

    @Override
    public boolean canHandle(Task task) {
        return task != null && task.assignedRole() == role;
    }

    @Override
    public Response handle(Request request, Context context) {
        String workingDirectory = asString(request.parameters().get("workingDir"), ".");
        List<String> command = asCommand(request.parameters().get("testCommand"));

        ToolExecutor.ExecutionResult result = toolExecutor.execute(new ToolExecutor.ExecutionRequest(
                workingDirectory, command, Map.of(), capabilities, timeout));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("command", command);
        output.put("exitCode", result.exitCode());
        output.put("timedOut", result.timedOut());
        output.put("stdout", truncate(result.stdout()));
        output.put("stderr", truncate(result.stderr()));

        if (result.success()) {
            return new Response(Outcome.COMPLETED, output, List.of(), Confidence.HIGH, List.of(), Optional.empty());
        }
        return new Response(Outcome.NEEDS_REVIEW, output, List.of(), Confidence.HIGH, List.of(),
                Optional.of("Tests failed (exit " + result.exitCode() + (result.timedOut() ? ", timed out" : "") + ")"));
    }

    private List<String> asCommand(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(Object::toString).toList();
        }
        return defaultTestCommand;
    }

    private static String asString(Object value, String fallback) {
        return value != null ? value.toString() : fallback;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_OUTPUT_CHARS ? text : text.substring(0, MAX_OUTPUT_CHARS) + "…[truncated]";
    }
}
