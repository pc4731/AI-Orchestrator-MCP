package com.orchestration.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestration.budget.TokenBudgetManager;
import com.orchestration.llm.LlmClient;
import com.orchestration.llm.PromptBuilder;
import com.orchestration.task.TaskId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Base class for LLM-backed agents (Template Method).
 *
 * <p>It owns the common flow every such agent follows:
 * <ol>
 *   <li>build a grounded user prompt from the task + input artifacts,</li>
 *   <li>call the {@link LlmClient} with the agent's configured model and (cacheable) system prompt,</li>
 *   <li>meter token usage against the {@link TokenBudgetManager}, escalating on a breach,</li>
 *   <li>parse the model's reply as the required JSON schema, re-prompting on a schema violation
 *       (the README's "structured outputs only" rule), and</li>
 *   <li>map it to a structured {@link Response} carrying confidence + assumptions.</li>
 * </ol>
 *
 * <p>Subclasses customise behaviour through {@link #buildUserPrompt} and {@link #afterParse}.
 */
public abstract class AbstractAgent implements Agent {

    /** The JSON contract every LLM agent must return. */
    protected static final String SCHEMA_INSTRUCTION = """
            Respond with ONLY a single JSON object (no prose, no code fences) of the form:
            {"status":"COMPLETED|NEEDS_REVIEW|ESCALATE|INSUFFICIENT_INFORMATION|FAILED",
             "confidence":"LOW|MEDIUM|HIGH",
             "assumptions":["..."],
             "output":{...},
             "artifacts":[{"path":"relative/path","content":"file contents"}],
             "escalationReason":"present only when you cannot proceed"}
            Ground every statement in the provided inputs. If you lack information, use status
            INSUFFICIENT_INFORMATION and explain in escalationReason rather than guessing.""";

    private final AgentId id;
    private final AgentRole role;
    private final java.util.Set<Capability> capabilities;
    private final com.orchestration.llm.ModelId model;
    private final String systemPrompt;
    private final Integer maxTokens;
    private final Double temperature;
    private final boolean cachePrompt;
    private final int maxSchemaRetries;
    private final LlmClient llm;
    private final TokenBudgetManager budget;

    protected final ObjectMapper mapper = new ObjectMapper();

    protected AbstractAgent(AgentSpec spec, LlmClient llm, TokenBudgetManager budget) {
        Objects.requireNonNull(spec, "spec");
        this.id = spec.id();
        this.role = spec.role();
        this.capabilities = spec.capabilities();
        this.model = spec.model();
        this.systemPrompt = spec.systemPrompt();
        this.maxTokens = spec.maxTokens();
        this.temperature = spec.temperature();
        this.cachePrompt = spec.cachePrompt();
        this.maxSchemaRetries = Math.max(0, spec.maxSchemaRetries());
        this.llm = Objects.requireNonNull(llm, "llm");
        this.budget = Objects.requireNonNull(budget, "budget");
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
    public java.util.Set<Capability> capabilities() {
        return capabilities;
    }

    @Override
    public boolean canHandle(com.orchestration.task.Task task) {
        return task != null && task.assignedRole() == role;
    }

    @Override
    public Response handle(Request request, Context context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        String basePrompt = buildUserPrompt(request, context);
        String prompt = basePrompt;

        for (int attempt = 0; attempt <= maxSchemaRetries; attempt++) {
            LlmClient.Request llmRequest = PromptBuilder.create()
                    .model(model)
                    .system(systemPrompt)
                    .cacheSystemPrompt(cachePrompt)
                    .maxTokens(maxTokens)
                    .temperature(temperature)
                    .user(prompt)
                    .build();

            LlmClient.Response llmResponse = llm.complete(llmRequest);

            Response budgetEscalation = recordUsage(context, request, llmResponse.usage());
            if (budgetEscalation != null) {
                return budgetEscalation;
            }

            try {
                JsonNode node = extractJson(llmResponse.content());
                Response base = mapToResponse(node);
                return afterParse(node, base, request, context);
            } catch (SchemaException e) {
                if (attempt < maxSchemaRetries) {
                    prompt = basePrompt + "\n\nYour previous reply was rejected: " + e.getMessage()
                            + "\nReturn ONLY the required JSON object.";
                    continue;
                }
                return new Response(Outcome.INSUFFICIENT_INFORMATION, Map.of(), List.of(),
                        Confidence.LOW, List.of(),
                        Optional.of("Could not produce valid structured output: " + e.getMessage()));
            }
        }
        throw new IllegalStateException("unreachable");
    }

    // ------------------------------------------------------------------------
    // Hooks for subclasses
    // ------------------------------------------------------------------------

    /** Build the task-specific user prompt. Subclasses typically prepend role-specific guidance. */
    protected String buildUserPrompt(Request request, Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(request.task().title()).append('\n');
        if (!request.task().description().isBlank()) {
            sb.append("Description: ").append(request.task().description()).append('\n');
        }
        if (request.instructions() != null && !request.instructions().isBlank()) {
            sb.append("Instructions: ").append(request.instructions()).append('\n');
        }
        if (!request.inputArtifacts().isEmpty()) {
            sb.append("\nGround your answer in these inputs:\n");
            request.inputArtifacts().forEach((key, value) ->
                    sb.append("--- ").append(key).append(" ---\n").append(value).append('\n'));
        }
        sb.append('\n').append(SCHEMA_INSTRUCTION);
        return sb.toString();
    }

    /** Post-process the mapped response (e.g. validate that required artifacts were produced). */
    protected Response afterParse(JsonNode node, Response base, Request request, Context context) {
        return base;
    }

    // ------------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------------

    private Response recordUsage(Context context, Request request, com.orchestration.llm.TokenUsage usage) {
        TaskId taskId = request.task() != null ? request.task().id() : null;
        TokenBudgetManager.BudgetDecision decision = budget.record(context.projectId(), taskId, usage);
        if (decision != TokenBudgetManager.BudgetDecision.WITHIN_BUDGET) {
            return new Response(Outcome.ESCALATE, Map.of("budget", decision.name()), List.of(),
                    Confidence.HIGH, List.of(), Optional.of("Token budget breached: " + decision));
        }
        return null;
    }

    private JsonNode extractJson(String content) {
        if (content == null) {
            throw new SchemaException("empty response");
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new SchemaException("no JSON object found in response");
        }
        try {
            return mapper.readTree(content.substring(start, end + 1));
        } catch (Exception e) {
            throw new SchemaException("invalid JSON: " + e.getMessage());
        }
    }

    private Response mapToResponse(JsonNode node) {
        String status = textOrNull(node, "status");
        if (status == null) {
            throw new SchemaException("missing required field 'status'");
        }
        String confidence = textOrNull(node, "confidence");
        if (confidence == null) {
            throw new SchemaException("missing required field 'confidence'");
        }
        Map<String, Object> output = node.has("output") ? toMap(node.get("output")) : toMap(node);
        return new Response(
                parseOutcome(status),
                output,
                readArtifacts(node.path("artifacts")),
                parseConfidence(confidence),
                readStringList(node.path("assumptions")),
                node.hasNonNull("escalationReason")
                        ? Optional.of(node.get("escalationReason").asText()) : Optional.empty());
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    protected Outcome parseOutcome(String raw) {
        return switch (raw.trim().toUpperCase()) {
            case "NEEDS_REVIEW", "REVIEW" -> Outcome.NEEDS_REVIEW;
            case "ESCALATE" -> Outcome.ESCALATE;
            case "INSUFFICIENT_INFORMATION", "INSUFFICIENT_INFO" -> Outcome.INSUFFICIENT_INFORMATION;
            case "FAILED", "FAIL" -> Outcome.FAILED;
            default -> Outcome.COMPLETED;
        };
    }

    protected Confidence parseConfidence(String raw) {
        return switch (raw.trim().toUpperCase()) {
            case "LOW" -> Confidence.LOW;
            case "HIGH" -> Confidence.HIGH;
            default -> Confidence.MEDIUM;
        };
    }

    private List<String> readStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> values.add(n.asText()));
        }
        return List.copyOf(values);
    }

    private List<Artifact> readArtifacts(JsonNode node) {
        List<Artifact> artifacts = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String path = textOrNull(item, "path");
                if (path == null) {
                    continue;
                }
                String content = item.hasNonNull("content") ? item.get("content").asText() : "";
                String mediaType = item.hasNonNull("mediaType") ? item.get("mediaType").asText() : "text/plain";
                artifacts.add(new Artifact(path, content, mediaType));
            }
        }
        return List.copyOf(artifacts);
    }

    protected Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    /** Signals that the model's reply did not satisfy the required schema and should be re-prompted. */
    protected static final class SchemaException extends RuntimeException {
        SchemaException(String message) {
            super(message);
        }
    }
}
