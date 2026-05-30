package com.orchestration.mcp;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentId;
import com.orchestration.agent.AgentRole;
import com.orchestration.agent.Capability;
import com.orchestration.task.Task;

import java.util.Objects;
import java.util.Set;

/**
 * An {@link Agent} whose "brain" is the MCP client (Claude Code). {@link #handle} packages the task
 * — persona, instructions, grounding, and the JSON schema to return — and parks it on the
 * {@link McpBridge}, blocking until Claude Code submits the result. From the engine's perspective it
 * behaves exactly like any other agent, so the task graph, checkpoints, and artifact commits all
 * work unchanged.
 */
public class McpAgent implements Agent {

    private final AgentId id;
    private final AgentRole role;
    private final Set<Capability> capabilities;
    private final String systemPrompt;
    private final McpBridge bridge;

    public McpAgent(AgentId id, AgentRole role, Set<Capability> capabilities,
                    String systemPrompt, McpBridge bridge) {
        this.id = Objects.requireNonNull(id, "id");
        this.role = Objects.requireNonNull(role, "role");
        this.capabilities = Set.copyOf(capabilities);
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.bridge = Objects.requireNonNull(bridge, "bridge");
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
        Task task = request.task();
        McpBridge.PendingTask pending = new McpBridge.PendingTask(
                task.id().value(),
                context.projectId(),
                role.name(),
                task.title(),
                task.description(),
                systemPrompt,
                instructions(request),
                schemaFor(role),
                request.inputArtifacts());
        return bridge.dispatch(pending);
    }

    private String instructions(Request request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Act as the ").append(role).append(" agent and complete this task.\n");
        if (request.instructions() != null && !request.instructions().isBlank()) {
            sb.append(request.instructions()).append('\n');
        }
        if (role == AgentRole.TEAM_LEAD) {
            sb.append("Decompose the request into concrete, role-assigned tasks (see schema). ")
                    .append("If it is too ambiguous, use status INSUFFICIENT_INFORMATION and list ")
                    .append("questions in output.questions instead of guessing.");
        } else if (role == AgentRole.BACKEND_DEVELOPER || role == AgentRole.FRONTEND_DEVELOPER) {
            sb.append("Return every file you create or change in the artifacts array ")
                    .append("(repository-relative path + full content).");
        } else if (role == AgentRole.QA_ENGINEER) {
            sb.append("Verify the implementation (run/inspect the tests) and report the result; ")
                    .append("use status NEEDS_REVIEW with details if anything fails.");
        }
        return sb.toString();
    }

    private String schemaFor(AgentRole role) {
        String base = """
                {"status":"COMPLETED|NEEDS_REVIEW|ESCALATE|INSUFFICIENT_INFORMATION|FAILED",
                 "confidence":"LOW|MEDIUM|HIGH","assumptions":["..."],"output":{...},
                 "artifacts":[{"path":"relative/path","content":"file contents"}],
                 "escalationReason":"only when you cannot proceed"}""";
        if (role == AgentRole.TEAM_LEAD) {
            return base + "\nFor decomposition, put tasks in output.tasks: "
                    + "[{\"id\":\"t1\",\"title\":\"...\",\"description\":\"...\","
                    + "\"role\":\"BACKEND_ARCHITECT\",\"dependsOn\":[\"t0\"]}] "
                    + "(roles: TEAM_LEAD, BACKEND_ARCHITECT, FRONTEND_ARCHITECT, UI_DESIGNER, "
                    + "BACKEND_DEVELOPER, FRONTEND_DEVELOPER, QA_ENGINEER, DBA, SECURITY_REVIEWER).";
        }
        return base;
    }
}
