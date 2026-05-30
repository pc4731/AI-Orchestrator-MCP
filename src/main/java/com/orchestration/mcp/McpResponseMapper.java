package com.orchestration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orchestration.agent.Agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses the JSON an agent (Claude Code) submits via {@code orchestrate_submit} into the engine's
 * structured {@link Agent.Response}. Mirrors the schema the LLM agents use, so both brains feed the
 * engine identical shapes.
 */
final class McpResponseMapper {

    private final ObjectMapper mapper = new ObjectMapper();

    Agent.Response parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            return failed("submitted result was not a JSON object");
        }
        Agent.Outcome outcome = parseOutcome(text(node, "status", "COMPLETED"));
        Agent.Confidence confidence = parseConfidence(text(node, "confidence", "MEDIUM"));
        Map<String, Object> output = node.has("output") ? toMap(node.get("output")) : Map.of();
        return new Agent.Response(
                outcome,
                output,
                readArtifacts(node.path("artifacts")),
                confidence,
                readStrings(node.path("assumptions")),
                node.hasNonNull("escalationReason")
                        ? Optional.of(node.get("escalationReason").asText()) : Optional.empty());
    }

    private Agent.Response failed(String reason) {
        return new Agent.Response(Agent.Outcome.FAILED, Map.of(), List.of(),
                Agent.Confidence.LOW, List.of(), Optional.of(reason));
    }

    private Agent.Outcome parseOutcome(String raw) {
        return switch (raw.trim().toUpperCase()) {
            case "NEEDS_REVIEW", "REVIEW" -> Agent.Outcome.NEEDS_REVIEW;
            case "ESCALATE" -> Agent.Outcome.ESCALATE;
            case "INSUFFICIENT_INFORMATION", "INSUFFICIENT_INFO" -> Agent.Outcome.INSUFFICIENT_INFORMATION;
            case "FAILED", "FAIL" -> Agent.Outcome.FAILED;
            default -> Agent.Outcome.COMPLETED;
        };
    }

    private Agent.Confidence parseConfidence(String raw) {
        return switch (raw.trim().toUpperCase()) {
            case "LOW" -> Agent.Confidence.LOW;
            case "HIGH" -> Agent.Confidence.HIGH;
            default -> Agent.Confidence.MEDIUM;
        };
    }

    private List<Agent.Artifact> readArtifacts(JsonNode node) {
        List<Agent.Artifact> artifacts = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (!item.hasNonNull("path")) {
                    continue;
                }
                artifacts.add(new Agent.Artifact(
                        item.get("path").asText(),
                        item.hasNonNull("content") ? item.get("content").asText() : "",
                        item.hasNonNull("mediaType") ? item.get("mediaType").asText() : "text/plain"));
            }
        }
        return List.copyOf(artifacts);
    }

    private List<String> readStrings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> values.add(n.asText()));
        }
        return List.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            return mapper.convertValue(obj, Map.class);
        }
        return Map.of();
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node.hasNonNull(field) ? node.get(field).asText() : fallback;
    }
}
