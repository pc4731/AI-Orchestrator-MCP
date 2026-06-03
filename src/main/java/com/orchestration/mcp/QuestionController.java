package com.orchestration.mcp;

import com.orchestration.agent.Agent;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The human side of the conversation, served to the web dashboard: the questions the team pauses to
 * ask (clarifications and plan confirmations) and the endpoint to answer them by voice or text. This
 * is what routes human↔team communication through the UI instead of the Claude Code chat — the
 * questions are parked on the {@link McpBridge} and answering one unblocks the waiting agent.
 *
 * <p>Only active under the {@code mcp} profile (where the bridge exists).
 */
@RestController
@RequestMapping("/api/questions")
@Profile("mcp")
public class QuestionController {

    private final McpBridge bridge;

    public QuestionController(McpBridge bridge) {
        this.bridge = bridge;
    }

    /** Open questions awaiting a human answer, oldest first. */
    @GetMapping
    public List<Map<String, Object>> pending() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (McpBridge.PendingTask t : bridge.pendingUserQuestions()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("taskId", t.taskId());
            q.put("title", t.title());
            q.put("question", t.description());
            // "confirm" questions want a yes/needs-changes; everything else is a free-text answer.
            q.put("kind", t.title() != null && t.title().toLowerCase().contains("confirm")
                    ? "confirm" : "clarify");
            out.add(q);
        }
        return out;
    }

    public record Answer(String text, Boolean approve) {
    }

    /** Submit the human's answer (spoken or typed), unblocking the team. */
    @PostMapping("/{taskId}/answer")
    public Map<String, Object> answer(@PathVariable String taskId, @RequestBody Answer answer) {
        String text = answer == null || answer.text() == null ? "" : answer.text().trim();
        boolean approve = answer == null || answer.approve() == null || answer.approve();
        // Provide every field the clarification gateway might read: ask() uses "answers";
        // confirm() uses "confirmed" + "corrections". The gateway picks the relevant one.
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("answers", text);
        output.put("confirmed", approve);
        output.put("corrections", text);
        Agent.Response response = new Agent.Response(Agent.Outcome.COMPLETED, output, List.of(),
                Agent.Confidence.HIGH, List.of(), Optional.empty());

        boolean accepted = bridge.complete(taskId, response);
        return Map.of("accepted", accepted,
                "message", accepted ? "Answer recorded — the team is continuing."
                        : "That question is no longer open.");
    }
}
