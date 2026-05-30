# AI Agent Orchestration Layer — Build Prompt

> Paste the prompt below to Claude Code to build this project. It is the single source of truth for what to build and how.

---

## PROMPT FOR CLAUDE CODE

### Project: Java-Based AI Agent Orchestration Layer for an AI Software Development Team

Build a production-grade, token-efficient agent orchestration layer in **Java 21 (LTS)** using **Spring Boot** and **Gradle**, that coordinates a team of specialized AI agents (backed by Anthropic's Claude API) to autonomously execute software development projects. Follow clean architecture, SOLID principles, and design for extensibility.

**Work incrementally.** First scaffold the architecture and core interfaces, explain your key decisions, and wait for my approval. Then implement components one at a time. Ask me clarifying questions before coding anything ambiguous — mirror the Team Lead philosophy of this very system. Flag every assumption explicitly instead of silently guessing.

---

### 1. V1 TECHNICAL STACK (use exactly this — do not substitute)

| Concern | v1 Choice | Notes |
|---|---|---|
| Language / Runtime | Java 21, Spring Boot, Gradle | LTS, virtual threads for concurrency |
| Message Bus | **In-memory async event bus** | Use Java `Flow`/`BlockingQueue` or Spring `ApplicationEventPublisher`. **Must sit behind a `MessageBus` interface** so Redis/Kafka can be swapped in later without engine changes. |
| Structured Memory + Checkpoints | **SQLite** (via JDBC) | Single-file, zero-setup. Stores task history, decisions, scenario→action mappings, checkpoints. Behind a `MemoryStore` interface. |
| Semantic Memory (RAG) | **Deferred** | Do NOT build the vector layer in v1. Leave a clean extension point (`SemanticMemory` interface) so PGVector can be added later. Structured memory + summarization handles v1 token savings. |
| Code Output | **Real files written to a Git repo** | Agents write to an actual working directory. Every agent action becomes a Git commit → free versioning, diffs, rollback, and audit trail. QA checks out and runs real code. |
| Tool Execution | **MCP tools, sandboxed** | Devs and QA get real execution tools (compile, build, run, test), scoped per agent, run in a container/sandbox so bad commands cannot harm the host. This is what makes the system verifiable instead of plausible-sounding. |
| LLM Client | Abstracted Anthropic client | Per-agent model selection, streaming, retry w/ exponential backoff, rate-limit handling, token accounting, prompt caching. |

---

### 2. CORE ARCHITECTURE

Build these layers:

- **Orchestration Engine** — central coordinator for agent lifecycle, task routing, and state.
- **Agent Abstraction Layer** — base `Agent` interface/abstract class so new agents are added without modifying the engine (Open/Closed).
- **Message Bus** — async, event-driven inter-agent communication, behind an interface (see stack table).
- **Task Graph / Workflow Engine** — work as a DAG of tasks with dependencies; parallel where possible, sequential where required.
- **State Machine** — explicit states for each task and the overall project: `PENDING`, `IN_PROGRESS`, `BLOCKED`, `NEEDS_CLARIFICATION`, `IN_REVIEW`, `FAILED`, `DONE`.
- **LLM Client Layer** — abstracted Anthropic API client (see stack table).

Use these patterns explicitly and document why each is used: **Strategy** (model/agent selection), **Mediator** (Team Lead), **Observer** (event bus), **State** (task lifecycle), **Chain of Responsibility** (escalation), **Factory** (agent creation), **Builder** (prompt construction).

---

### 3. AGENTS

Each agent has: a system prompt, an assigned Claude model, structured JSON input/output schemas, and explicit tools/capabilities. **Model assignment must be configurable per agent via YAML — never hardcoded.**

| Agent | Model | Responsibilities |
|---|---|---|
| **Team Lead / Business Analyst** | Opus | Understands user tasks; asks the user clarifying questions; single escalation point for all agents; decomposes & delegates tasks; owns the task graph. |
| **Backend Architect** | Opus | Designs scalable backend architecture; selects design patterns & tech stack; deep research; ensures security; consults other agents (e.g. DBA); issues coding instructions to backend dev. |
| **Frontend Architect** | Opus | Designs scalable frontend architecture; selects patterns & tech stack; deep research; security; consults backend architect; issues coding instructions to frontend dev. |
| **UI Designer** | Sonnet | Produces user-friendly, responsive UI designs; chooses color scheme based on client/application context; outputs design specs/tokens. |
| **Backend Developer** | Opus/Sonnet (configurable) | Implements code from architect spec; industry standards; time/space-complexity aware; latest syntax; nothing hardcoded — everything configurable. |
| **Frontend Developer** | Opus/Sonnet (configurable) | Implements UI from architect + designer specs; builds reusable components; standards & complexity aware. |
| **QA Engineer** | Opus/Sonnet (configurable) | Tests for bugs and bad UX; tries to break the app; validates functionality; reports reproducible bugs with clear explanations to the Team Lead. |
| **DBA / Data Architect** | Opus | Schema design, query optimization, data modeling; consulted by architects. |
| **Security / DevSecOps Reviewer** | Opus | Audits architecture & code against OWASP Top 10; required since the system must build secure applications. |

---

### 4. ROBUSTNESS COMPONENTS

- **Human-in-the-loop gates** — explicit checkpoints where the Team Lead pauses for human approval (clarifications, architecture sign-off, deployment).
- **Bug feedback loop** — QA → Team Lead → Developer as a state-machine loop with a **max-retry / escalation limit** to prevent infinite loops.
- **Artifact/Output Repository** — persistent, versioned storage of all artifacts (architecture docs, code, designs, test reports) keyed by task ID (Git-backed).
- **Cost/Token Budget Manager** — enforce per-task and per-project token budgets; halt or escalate on breach.
- **Audit Log** — full traceability of every agent decision, prompt, and response.

---

### 5. ANTI-HALLUCINATION & GROUNDING (critical)

- **Structured outputs only** — agents respond in validated JSON schemas; reject and re-prompt on schema violation.
- **Grounding & escalation** — agents reference source artifacts (task spec, existing code, prior decisions) and must emit `"INSUFFICIENT_INFORMATION"` and escalate to the Team Lead rather than guess.
- **Verification step** — critical outputs (architecture, code) pass a self-check/validator before acceptance.
- **No fabricated APIs/libraries** — developers use only declared, verifiable dependencies; unknown libraries are flagged for confirmation. MCP execution (compile/build) catches fabrications directly.
- **Confidence reporting** — every output carries a confidence level and an explicit assumptions list.

---

### 6. TOKEN EFFICIENCY & MEMORY (critical)

- **Short-term (working) memory** — current task context only; trimmed aggressively.
- **Long-term memory (SQLite)** — past decisions, reusable solutions, scenario→action mappings, code patterns, resolved bugs. Behind a `MemoryStore` interface.
- **Summarization** — completed task threads compressed into compact memory entries instead of carrying full transcripts.
- **Prompt caching** — use Anthropic prompt caching for stable system prompts and shared context.
- **Deduplication** — detect repeated subtasks and reuse cached results instead of re-calling the LLM.
- **Checkpointing & resumption** — persist full orchestration state (task graph, agent states, partial outputs) to disk after every step. **On token-budget renewal or restart, resume exactly where it stopped by loading the last checkpoint and referencing memory — never restart work or repeat completed steps.**
- **Semantic retrieval (RAG)** — deferred to a later version; leave the `SemanticMemory` extension point in place.

---

### 7. DELIVERABLES

1. Full Gradle Java project that compiles and runs.
2. Project `README` with a Mermaid architecture diagram, setup, and run instructions.
3. `config/` with externalized YAML for: model-per-agent, token budgets, API settings, memory backend, message bus backend, sandbox settings.
4. Base interfaces + concrete implementations of every agent and core engine component.
5. End-to-end demo: feed a sample feature request and show the flow — clarifying questions → architecture → design → code (real files committed to Git) → QA report (real test run) — demonstrating memory and checkpoint resumption.
6. Unit tests for the engine, state machine, memory store, token manager, and bug-loop limits.
7. Documented extension points for adding new agents, swapping the message bus, and adding the vector RAG layer.

---

### 8. EXECUTION RULES

- **Do not hardcode** anything that belongs in config (models, prompts, budgets, endpoints, paths).
- **Build incrementally** — scaffold + interfaces first, get my approval, then implement agents one by one.
- **Ask clarifying questions** before starting if anything is ambiguous.
- **Sandbox all tool execution** — no agent command runs against the host directly.
- Write production-quality, well-documented, testable code following Java best practices.
- Explain key design decisions briefly as you go, and flag every assumption.

**Start by proposing the project structure and the core interfaces (`Agent`, `MessageBus`, `MemoryStore`, `SemanticMemory`, `LlmClient`, `TaskGraph`, `OrchestrationEngine`), then wait for my confirmation before implementing.**
