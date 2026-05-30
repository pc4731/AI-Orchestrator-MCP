# AI Agent Orchestration Layer

A production-grade, token-efficient orchestration layer in **Java 21 + Spring Boot** that coordinates
a team of specialized AI agents (backed by Anthropic's Claude API) to autonomously execute software
development projects. Clean architecture, SOLID, and designed for extensibility.

> The original build specification this project was built from is preserved at
> [`docs/BUILD_PROMPT.md`](docs/BUILD_PROMPT.md).

---

## Architecture

```mermaid
flowchart TB
    user([User · feature request]) --> engine

    subgraph core [Orchestration Engine]
        engine[OrchestrationEngine<br/>virtual-thread dispatch · checkpoint-after-step · gates]
        graph[TaskGraph DAG<br/>+ WorkflowStateMachine]
        engine --- graph
    end

    engine -->|plan| planner[TeamLeadProjectPlanner]
    engine -->|dispatch ready tasks| processor[AgentTaskProcessor]
    planner --> factory[AgentFactory]
    processor --> factory

    subgraph agents [Agents]
        tl[TeamLead]
        adv[Architects · DBA · Security · UI Designer]
        dev[Developers]
        qa[QA Engineer]
    end
    factory --> agents

    agents -->|complete / stream| llm[LlmClient → Anthropic Claude]
    qa -->|compile · build · test| tools[ToolExecutor → Docker sandbox]
    processor -->|commit files| artifacts[ArtifactRepository → Git]
    engine -->|checkpoints| memory[MemoryStore → SQLite]
    agents -->|usage| budget[TokenBudgetManager]
    engine --> audit[AuditLog]
    agents -.events.- bus[MessageBus]
    rag[SemanticMemory → NoOp · RAG deferred]:::deferred

    classDef deferred stroke-dasharray: 5 5,fill:#f7f7f7;
```

**Flow:** the Team Lead decomposes the request into a task DAG → the engine dispatches ready tasks
across virtual threads → architects/designers produce specs → developers write real files (committed
to Git) → QA runs real tests in a sandbox → results flow back through the state machine, with a
checkpoint after every step so the run can resume exactly where it stopped.

---

## Tech stack (v1)

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 (LTS), virtual threads |
| Framework / build | Spring Boot 3.3, Gradle (Groovy DSL) |
| Message bus | In-memory async (`MessageBus` interface → Redis/Kafka later) |
| Structured memory + checkpoints | SQLite via JDBC (`MemoryStore` interface) |
| Semantic memory (RAG) | Deferred — `SemanticMemory` no-op extension point |
| Code output | Real files committed to a Git repo (JGit) |
| Tool execution | Sandboxed via Docker (`ToolExecutor` interface) |
| LLM client | Thin custom Anthropic client over `java.net.http` |

---

## Design patterns

| Pattern | Where |
|---|---|
| **Strategy** | per-agent model selection; swappable bus/memory backends |
| **Mediator** | `OrchestrationEngine` (system) and Team Lead (agents) |
| **Observer** | `MessageBus` event delivery |
| **State** | `WorkflowState` + `WorkflowStateMachine` |
| **Chain of Responsibility** | escalation → human-in-the-loop gates |
| **Factory** | `ConfigurableAgentFactory` |
| **Builder** | `PromptBuilder` |
| **Template Method** | `AbstractAgent` |

---

## Project structure

```
agent-orchestration/
├── build.gradle · settings.gradle · gradlew         # Gradle (wrapper pinned to 8.10.2)
├── config/                                           # externalized YAML (nothing hardcoded)
│   ├── llm.yml  agents.yml  budgets.yml
│   ├── memory.yml  messagebus.yml  sandbox.yml
├── prompts/                                          # one system prompt per agent
├── docs/BUILD_PROMPT.md                              # original spec
└── src/main/java/com/orchestration/
    ├── agent/    Agent · AbstractAgent · TeamLead/Developer/Qa/Generic · ConfigurableAgentFactory
    ├── engine/   OrchestrationEngine · DefaultOrchestrationEngine · AgentTaskProcessor · TeamLeadProjectPlanner
    ├── task/     TaskGraph · InMemoryTaskGraph · WorkflowState(Machine) · Task
    ├── bus/      MessageBus · InMemoryMessageBus
    ├── memory/   MemoryStore · SqliteMemoryStore · SemanticMemory · NoOpSemanticMemory
    ├── llm/      LlmClient · AnthropicLlmClient · PromptBuilder
    ├── tools/    ToolExecutor · DockerToolExecutor
    ├── artifact/ ArtifactRepository · JGitArtifactRepository
    ├── budget/ · audit/ · config/ · demo/
```

---

## Setup

Requires a JDK to launch Gradle. **Java 21 itself is provisioned automatically** by the Gradle
toolchain (foojay resolver) — you don't need to install it manually.

```bash
# build + run the full test suite
./gradlew build

# tests only
./gradlew test
```

> Note: the build runs through the Gradle **wrapper** (`./gradlew`, pinned to 8.10.2). The Spring
> Boot 3.3 plugin does not support Gradle 9, so use the wrapper rather than a system `gradle`.

---

## Run the demo (offline — no API key, no Docker)

The `demo` profile swaps in a canned LLM and a stub sandbox so the whole flow runs locally:

```bash
./gradlew bootRun --args='--spring.profiles.active=demo'
```

It submits a sample feature request and prints: the decomposition, the **audit timeline**, the
**code committed to Git**, the **SQLite checkpoints**, and a **resume** that repeats no completed
work.

## Run against the real Anthropic API

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./gradlew bootRun
```

Real runs use `AnthropicLlmClient` and execute tools in Docker (requires a running Docker daemon).
Models, budgets, prompts, and sandbox limits are all set in `config/*.yml`.

---

## Anti-hallucination & grounding

- **Structured outputs only** — agents must return a validated JSON schema; invalid replies are
  re-prompted (`AbstractAgent`).
- **Grounding & escalation** — inputs are passed explicitly into prompts; agents emit
  `INSUFFICIENT_INFORMATION` and escalate to a human gate rather than guess.
- **Verification by execution** — QA runs real tests in the sandbox; a developer that claims "done"
  with no code is downgraded to `NEEDS_REVIEW`.
- **Confidence + assumptions** on every agent response; full **audit log** of every step.

---

## Extension points

- **Add an agent** — add an entry to `config/agents.yml` (and, for genuinely new behaviour, a case
  in `ConfigurableAgentFactory`). The engine never changes.
- **Swap the message bus** — implement `MessageBus` (e.g. Redis/Kafka) and bind it in
  `InfrastructureConfig`.
- **Add vector RAG** — implement `SemanticMemory` (e.g. PGVector) to replace `NoOpSemanticMemory`.

---

## Testing

`./gradlew test` runs the full suite, including an end-to-end test
(`EngineAgentsEndToEndTest`) that drives Team Lead → architect → developer → committed code with a
scripted LLM (no network), plus unit tests for the engine, state machine, task graph, memory store,
token budget, bug-loop limits, LLM client, and agents.
