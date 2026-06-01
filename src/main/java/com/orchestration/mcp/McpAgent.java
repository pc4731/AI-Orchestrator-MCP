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
        sb.append(roleGuidance(role));
        return sb.toString();
    }

    /** Role-specific guidance appended to every task so each agent knows what to produce. */
    private String roleGuidance(AgentRole role) {
        return switch (role) {
            case BUSINESS_ANALYST ->
                    "Elicit and clarify the requirements before any building happens. Probe HARD for "
                            + "unstated needs: full feature scope (name likely-but-unstated features and "
                            + "ask), exact UI expectations and fidelity (e.g. 'rebuild every component "
                            + "as real HTML/CSS' vs a screenshot — never accept a shortcut), theme/"
                            + "branding, data/persistence + retention, integrations, and non-functional "
                            + "needs (scale, auth, security, browsers/devices). If anything material is "
                            + "missing or ambiguous, set status INSUFFICIENT_INFORMATION and put concise, "
                            + "specific questions in output.questions — ASK THE USER, incorporate the "
                            + "answers, and only then produce a precise, testable specification in "
                            + "output.specification with explicit acceptance criteria.";
            case PROMPT_ENGINEER ->
                    "Rewrite the downstream task into a crisp, self-contained prompt for the named "
                            + "target role. In output.refinedPrompt give: the goal, the concrete inputs "
                            + "to ground in, hard constraints, and explicit ACCEPTANCE CRITERIA the work "
                            + "must meet (e.g. 'every component rendered as real markup, not an image'). "
                            + "Be precise and token-efficient; remove ambiguity, do not add scope.";
            case TEAM_LEAD ->
                    "Decompose the agreed specification into concrete, role-assigned tasks (the "
                            + "Business Analyst has already clarified requirements; if the spec is still "
                            + "ambiguous, set INSUFFICIENT_INFORMATION with questions in output.questions "
                            + "rather than guessing). Cover the FULL scope: a UI_DESIGNER task when there "
                            + "is a UI, a DBA task for meaningful data, a SECURITY_REVIEWER task when "
                            + "handling user data, and a QA_ENGINEER task that verifies the result "
                            + "matches the acceptance criteria. Add review dependencies so work is "
                            + "checked, not just produced. Record decisions in output.assumptions.";
            case BACKEND_ARCHITECT ->
                    "Produce the backend architecture in output: chosen patterns, components, tech "
                            + "stack (with rationale), data model, failure modes, and security "
                            + "considerations. Put concrete, implementable instructions for the backend "
                            + "developer in output.instructions. Do not write application code; do not "
                            + "invent libraries — flag anything uncertain.";
            case FRONTEND_ARCHITECT ->
                    "Produce the frontend architecture in output: component structure, state "
                            + "management, routing, and tech stack (with rationale), aligned to the "
                            + "backend and the UI design. Put implementable instructions for the "
                            + "frontend developer in output.instructions. Address accessibility and "
                            + "performance. Do not write application code.";
            case UI_DESIGNER ->
                    "Produce a UI design spec in output: layout, component inventory, interaction "
                            + "patterns, and an explicit theme — color palette (with light/dark), type "
                            + "scale, and spacing as design tokens in output.tokens. Keep it accessible "
                            + "(contrast, focus states) and responsive.";
            case DBA ->
                    "Produce the data design in output: normalized schema (tables/columns/types), "
                            + "keys, indexes, and constraints, plus notable access patterns and "
                            + "trade-offs. Provide schema DDL in output.schema. Ground every table in a "
                            + "real requirement; do not invent fields.";
            case SECURITY_REVIEWER ->
                    "Audit the architecture/code against the OWASP Top 10. Put findings in "
                            + "output.findings as a list of {issue, severity, remediation}. Check "
                            + "authn/authz, input validation, secrets handling, dependency risk, and "
                            + "data exposure. Set status NEEDS_REVIEW if there are blocking issues.";
            case BACKEND_DEVELOPER, FRONTEND_DEVELOPER ->
                    "Implement the code AND its automated tests from the architect's (and designer's) "
                            + "spec. BUILD IT FOR REAL — reconstruct every component faithfully as actual "
                            + "code/markup; never fake the result with a screenshot, a background image, "
                            + "a stub, or placeholder text. Meet every acceptance criterion in the "
                            + "refined prompt. Return every file you create or change in the artifacts "
                            + "array (repository-relative path + full content), INCLUDING test files that "
                            + "cover each feature (happy path + key edge cases) and run under the "
                            + "project's standard test command. Use only declared, verifiable "
                            + "dependencies; never invent APIs. Summarize in output.summary.";
            case QA_ENGINEER ->
                    "Verify the implementation by actually RUNNING the project's test command in the "
                            + "repo (use the testCommand and workingDir provided), not by reasoning. "
                            + "Report the real result; if there are no tests or they don't exercise the "
                            + "features, say so and set status NEEDS_REVIEW. Use NEEDS_REVIEW with "
                            + "reproducible details on any failure.";
        };
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
