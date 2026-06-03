package com.orchestration.mcp;

import com.orchestration.agent.Agent;
import com.orchestration.agent.AgentId;
import com.orchestration.agent.AgentRole;
import com.orchestration.agent.Capability;
import com.orchestration.budget.TokenBudgetManager;
import com.orchestration.llm.TokenUsage;
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

    /** Rough chars-per-token for estimating usage from text sizes (Claude Code reports no exact
     *  counts to the tool, so the meter is approximate-but-proportional — enough for a ceiling). */
    private static final int CHARS_PER_TOKEN = 4;

    private final AgentId id;
    private final AgentRole role;
    private final Set<Capability> capabilities;
    private final String systemPrompt;
    private final McpBridge bridge;
    private final TokenBudgetManager budget; // optional; null disables MCP usage metering

    public McpAgent(AgentId id, AgentRole role, Set<Capability> capabilities,
                    String systemPrompt, McpBridge bridge) {
        this(id, role, capabilities, systemPrompt, bridge, null);
    }

    public McpAgent(AgentId id, AgentRole role, Set<Capability> capabilities,
                    String systemPrompt, McpBridge bridge, TokenBudgetManager budget) {
        this.id = Objects.requireNonNull(id, "id");
        this.role = Objects.requireNonNull(role, "role");
        this.capabilities = Set.copyOf(capabilities);
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.budget = budget;
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
        String instructions = instructions(request);
        McpBridge.PendingTask pending = new McpBridge.PendingTask(
                task.id().value(),
                context.projectId(),
                role.name(),
                task.title(),
                task.description(),
                systemPrompt,
                instructions,
                schemaFor(role),
                request.inputArtifacts());
        Response response = bridge.dispatch(pending);
        meter(context, task, request, instructions, response);
        return response;
    }

    /**
     * Estimate this turn's tokens from the text the engine actually exchanged (prompt + grounding in,
     * structured output + artifacts out) and record it against the budget. Claude Code doesn't report
     * exact counts to the tool, so this is an approximation — good enough for a cost circuit breaker.
     */
    private void meter(Context context, Task task, Request request, String instructions,
                       Response response) {
        if (budget == null) {
            return;
        }
        long inChars = length(systemPrompt) + length(instructions) + length(task.description());
        for (String g : request.inputArtifacts().values()) {
            inChars += length(g);
        }
        long outChars = length(String.valueOf(response.structuredOutput()))
                + length(response.escalationReason().orElse(""));
        for (Agent.Artifact a : response.artifacts()) {
            outChars += length(a.content());
        }
        TokenUsage estimate = new TokenUsage(inChars / CHARS_PER_TOKEN, outChars / CHARS_PER_TOKEN, 0, 0);
        budget.record(context.projectId(), task.id(), estimate);
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
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
            case MARKET_RESEARCHER ->
                    "Research the market for the planned functionality using your web tools (WebSearch/"
                            + "WebFetch). Find comparable/competing tools, study their reviews, issue "
                            + "trackers, and forum threads, and identify the RECURRING COMPLAINTS users "
                            + "have about them. Then turn that into action for THIS build. Return: "
                            + "output.competitors (a list of {name, url, summary}); output.complaints (a "
                            + "list of {complaint, source} of the most common pain points in similar "
                            + "tools); output.recommendedFeatures (a list of {feature, rationale, priority "
                            + "HIGH|MEDIUM|LOW} — features worth adding, including ones that fix those "
                            + "complaints); and output.plan (concrete steps the team should take so the "
                            + "final product avoids those complaints and ships the high-value features). "
                            + "Put a short narrative in output.summary. Cite real sources you actually "
                            + "fetched; never invent URLs or quotes — if web access is unavailable, say so "
                            + "and base recommendations on well-known patterns instead.";
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
                            + "rather than guessing). Fold in the Market Researcher's recommendedFeatures "
                            + "and plan (provided as grounding) so the build is feature-rich and avoids "
                            + "the complaints common to similar tools. Cover the FULL scope: a UI_DESIGNER "
                            + "task when there is a UI, a DBA task for meaningful data, a SECURITY_REVIEWER "
                            + "task when handling user data, and a QA_ENGINEER task that verifies the "
                            + "result matches the acceptance criteria AND that the project builds green. "
                            + "ALWAYS include a final documentation task (assigned to a developer) that "
                            + "produces a RUN.md at the repo root with exact steps to install, build, run, "
                            + "and test the app — make it depend on all implementation tasks. Add review "
                            + "dependencies so work is checked, not just produced. Record decisions in "
                            + "output.assumptions.";
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
            case AI_ML_ARCHITECT ->
                    "Design the AI/ML capability — but FIRST decide whether it even needs AI. If the "
                            + "feature can be met with conventional, deterministic libraries (e.g. a rules "
                            + "engine, full-text search, regex/NLP libs, classical ML offline), you MUST "
                            + "set status INSUFFICIENT_INFORMATION and put questions in output.questions "
                            + "asking the user to choose: (a) the AI-powered approach — which REQUIRES an "
                            + "AI API key and ongoing cost — or (b) the non-AI library approach; state the "
                            + "trade-offs (cost, latency, accuracy, privacy, offline). If the AI approach "
                            + "is chosen or AI is genuinely required, ALSO ask which model/provider to use "
                            + "— Anthropic, OpenAI, Google Gemini, or another (and note they must supply "
                            + "that provider's API key). Only once the user has answered, produce the "
                            + "architecture in output.instructions: chosen provider+model, prompt/inference "
                            + "design, data flow, fallbacks, evaluation, cost/latency, and API-key handling "
                            + "(read from env/config, NEVER hardcoded). Do not write application code.";
            case AI_ML_DEVELOPER ->
                    "Implement the AI/ML feature exactly as the AI/ML Architect specified — using the "
                            + "chosen provider's official SDK, or the agreed non-AI library if that path "
                            + "was selected. Read the API key from environment/config and fail gracefully "
                            + "with a clear message when it is absent; NEVER hardcode keys or commit "
                            + "secrets. Build it for real (no stubbed model responses in the product). "
                            + "Return all files as artifacts INCLUDING tests that MOCK external model "
                            + "calls (never hit a paid API in tests). Document the required API key and how "
                            + "to set it in RUN.md. Summarize in output.summary.";
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
            case CODE_REVIEWER ->
                    "Review the implemented code (provided as grounding from the developers) for "
                            + "correctness, readability, maintainability, naming, error handling, test "
                            + "coverage, and adherence to the project's conventions and the acceptance "
                            + "criteria — this is a quality review, distinct from the security audit. Put "
                            + "findings in output.findings as a list of {file, issue, severity, "
                            + "suggestion}, and a short verdict in output.summary. Set status "
                            + "NEEDS_REVIEW (with specifics) if there are blocking quality issues so the "
                            + "developer reworks them; otherwise COMPLETED. The ACTUAL committed code is "
                            + "in the projectFiles grounding — review against the real files, not just "
                            + "the developers' summaries. You are done once you report findings; a "
                            + "developer will be dispatched to fix them. Do not rewrite the app yourself.";
            case CONTENT_WRITER ->
                    "Write the product's copy from the spec and design: UI strings/microcopy, empty/error "
                            + "states, onboarding, help/docs, and any marketing content requested. Match a "
                            + "consistent voice and the target audience, keep it accurate (never invent "
                            + "facts/claims), and make it accessible (plain language, descriptive link "
                            + "text, alt-text suggestions). Return the copy as artifacts where it belongs "
                            + "in the repo (e.g. content files, README/docs, i18n strings) and summarize "
                            + "in output.summary. Coordinate with the SEO Expert on terminology.";
            case SEO_EXPERT ->
                    "Optimise the content and markup for search visibility WITHOUT harming UX or "
                            + "accessibility. Provide: output.keywords (primary/secondary with intent), "
                            + "per-page output.metadata (title ≤60 chars, meta description ≤155, canonical, "
                            + "Open Graph/Twitter, structured-data/JSON-LD suggestions), and "
                            + "output.recommendations for semantic HTML (heading hierarchy, alt text, "
                            + "internal linking, sitemap/robots). Where you change files (meta tags, "
                            + "sitemap.xml, robots.txt), return them as artifacts. Ground keywords in the "
                            + "real product; never keyword-stuff.";
            case BACKEND_DEVELOPER, FRONTEND_DEVELOPER ->
                    "Implement the code AND its automated tests from the architect's (and designer's) "
                            + "spec. BUILD IT FOR REAL — reconstruct every component faithfully as actual "
                            + "code/markup; never fake the result with a screenshot, a background image, "
                            + "a stub, or placeholder text. Meet every acceptance criterion in the "
                            + "refined prompt. Return every file you create or change in the artifacts "
                            + "array (repository-relative path + full content), INCLUDING test files that "
                            + "cover each feature (happy path + key edge cases) and run under the "
                            + "project's standard test command. The project MUST build and the tests MUST "
                            + "pass before you report COMPLETED — if a build error (provided as the "
                            + "buildFailure grounding) is given, fix it for real and return the corrected "
                            + "files. When asked to write run instructions, produce a RUN.md at the repo "
                            + "root covering install, build, run, and test steps. Use only declared, "
                            + "verifiable dependencies; never invent APIs. Summarize in output.summary.";
            case DEVOPS_ENGINEER ->
                    "Make the project shippable using ONLY the stack the team actually built (read it "
                            + "from the upstream grounding and the repo — never assume a different "
                            + "language/framework). Produce, as artifacts: a Dockerfile (multi-stage, "
                            + "non-root, minimal base) and a .dockerignore; a docker-compose.yml when "
                            + "there are multiple services or a datastore; a CI workflow at "
                            + ".github/workflows/ci.yml that installs, builds, and runs the project's "
                            + "tests (use the provided testCommand); and deployment/config notes plus a "
                            + ".env.example for required configuration (NEVER commit real secrets — read "
                            + "them from env). Keep everything consistent with RUN.md. Validate that the "
                            + "files reference real paths/commands; do not invent services the app does "
                            + "not have. Summarize what you added and how to deploy in output.summary.";
            case PROJECT_EXPLAINER ->
                    "Read the existing, prebuilt project at the given path and explain it. Explore the "
                            + "real files with your tools — entry points, build/config files, source, "
                            + "tests, and docs — then explain WHAT it does and HOW: purpose and main "
                            + "features; architecture and how the pieces fit; tech stack and key "
                            + "dependencies; a map of the important files/modules; how to build, run, and "
                            + "test it; and notable patterns, risks, or gotchas. Ground every statement in "
                            + "files you actually read — never guess or invent behaviour; if something is "
                            + "unclear, say so. Do NOT modify any files. Put the full explanation in "
                            + "output.explanation as Markdown and a one-paragraph output.summary.";
            case RETROSPECTIVE_ANALYST ->
                    "Run a retrospective on the project you just orchestrated, focused ONLY on friction "
                            + "with the ORCHESTRATION SYSTEM itself — not bugs in the built project. Think "
                            + "about where the tooling slowed the team or forced a workaround: missing "
                            + "roles/capabilities, rigid schemas, weak hand-offs/context, the budget "
                            + "cutting work off, no way to ask the user or run a command, repeated rework "
                            + "or blocked tasks, anything you wished the orchestrator did. Put a list in "
                            + "output.improvements where each item is {problem, impact (how it hurt this "
                            + "run), suggestion (a concrete change to the orchestrator), severity "
                            + "HIGH|MEDIUM|LOW}, and a short output.summary. Be specific and grounded in "
                            + "what actually happened this run; if the run went smoothly, say so and "
                            + "return few or no items. Do NOT suggest changes to the built project.";
            case KNOWLEDGE_CURATOR ->
                    "Distil everything the team produced (provided as grounding: the spec, market "
                            + "research, architecture, schema, UI design, code summaries, and the prior "
                            + "projectKnowledge if this is an edit) into a concise, structured project "
                            + "brief whose ONLY purpose is to let a future session understand the project "
                            + "WITHOUT re-reading the whole codebase. Put the full brief in "
                            + "output.knowledge as Markdown covering: what the project does and for whom; "
                            + "the architecture and how the pieces fit; the tech stack and key "
                            + "dependencies; a module/file map (path → responsibility); important design "
                            + "decisions and trade-offs (and why); data model; how to build/run/test; and "
                            + "known gotchas/TODOs. If prior projectKnowledge exists, UPDATE it to reflect "
                            + "this change rather than rewriting from scratch. Be accurate and grounded — "
                            + "never invent files or behaviour. Keep it TIGHT (a fast, complete mental "
                            + "model, not exhaustive prose). Do NOT return any artifacts: the system "
                            + "commits the brief as the project knowledge file itself. Everything goes in "
                            + "output.knowledge.";
            case QA_ENGINEER ->
                    "Verify the implementation by actually RUNNING the project's test command in the "
                            + "repo (use the testCommand and workingDir provided), not by reasoning. The "
                            + "project MUST build/compile and the tests MUST pass: if the build is broken "
                            + "or tests fail, set status NEEDS_REVIEW with the exact error output — a "
                            + "broken build must never pass review (a developer will be re-dispatched to "
                            + "fix it). Also confirm a RUN.md with build/run/test steps exists at the repo "
                            + "root; if it is missing, set NEEDS_REVIEW. If there are no tests or they "
                            + "don't exercise the features, say so and set NEEDS_REVIEW. Always include "
                            + "reproducible details on any failure. The actual committed code is in the "
                            + "projectFiles grounding — verify against the real files, not summaries.";
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
                    + "(roles: TEAM_LEAD, MARKET_RESEARCHER, BACKEND_ARCHITECT, FRONTEND_ARCHITECT, "
                    + "AI_ML_ARCHITECT, UI_DESIGNER, BACKEND_DEVELOPER, FRONTEND_DEVELOPER, "
                    + "AI_ML_DEVELOPER, QA_ENGINEER, DBA, SECURITY_REVIEWER, CODE_REVIEWER, "
                    + "CONTENT_WRITER, SEO_EXPERT, DEVOPS_ENGINEER, KNOWLEDGE_CURATOR). Add a "
                    + "DEVOPS_ENGINEER task (containerisation + CI + deploy config), depending on the "
                    + "implementation tasks, whenever the result is a runnable app or service. Only add "
                    + "the AI_ML_* roles when "
                    + "the app actually needs AI/ML — the AI/ML Architect will confirm AI-vs-library and "
                    + "the provider with the user. ONLY when the grounding says rememberProject=true, end "
                    + "the graph with a single KNOWLEDGE_CURATOR task that depends on all other tasks (it "
                    + "records a committed project brief so a future session has full context without "
                    + "re-reading the code); when rememberProject=false, do NOT add a curator task.";
        }
        return base;
    }
}
