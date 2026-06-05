package com.orchestration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * A minimal Model Context Protocol server over stdio (newline-delimited JSON-RPC 2.0). Claude Code
 * launches this process and exchanges messages on stdin/stdout; this class implements the handshake
 * ({@code initialize}, {@code tools/list}) and routes {@code tools/call} to
 * {@link OrchestrationMcpService}.
 *
 * <p><b>stdout is the protocol channel</b> — nothing else may be written there (logging goes to
 * stderr via {@code logback-mcp.xml}); each message is one JSON object on its own line.
 */
public class JsonRpcMcpServer {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final OrchestrationMcpService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private PrintWriter out;

    public JsonRpcMcpServer(OrchestrationMcpService service) {
        this.service = service;
    }

    /** Run the read/dispatch loop until stdin is closed. Blocks the calling thread. */
    public void serve(InputStream in, OutputStream rawOut) {
        this.out = new PrintWriter(new java.io.OutputStreamWriter(rawOut, StandardCharsets.UTF_8), false);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handleLine(line);
            }
        } catch (Exception e) {
            System.err.println("[mcp] server loop ended: " + e);
        }
    }

    private void handleLine(String line) {
        JsonNode message;
        try {
            message = mapper.readTree(line);
        } catch (Exception e) {
            System.err.println("[mcp] dropping malformed message: " + e.getMessage());
            return;
        }
        String method = message.path("method").asText(null);
        JsonNode id = message.get("id");
        if (method == null) {
            return; // a response/ack we don't track
        }
        try {
            switch (method) {
                case "initialize" -> sendResult(id, initializeResult(message.path("params")));
                case "tools/list" -> sendResult(id, toolsListResult());
                case "tools/call" -> sendResult(id, toolsCallResult(message.path("params")));
                case "ping" -> sendResult(id, mapper.createObjectNode());
                default -> {
                    if (id != null) {
                        sendError(id, -32601, "Method not found: " + method);
                    }
                    // otherwise a notification (e.g. notifications/initialized) — no response
                }
            }
        } catch (Exception e) {
            if (id != null) {
                sendError(id, -32603, "Internal error: " + e.getMessage());
            }
            System.err.println("[mcp] error handling " + method + ": " + e);
        }
    }

    // ------------------------------------------------------------------------
    // Protocol handlers
    // ------------------------------------------------------------------------

    private ObjectNode initializeResult(JsonNode params) {
        ObjectNode result = mapper.createObjectNode();
        String requested = params.path("protocolVersion").asText(PROTOCOL_VERSION);
        result.put("protocolVersion", requested);
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.set("tools", mapper.createObjectNode());
        result.set("capabilities", capabilities);
        ObjectNode serverInfo = mapper.createObjectNode();
        serverInfo.put("name", "agent-orchestration");
        serverInfo.put("version", "0.1.0");
        result.set("serverInfo", serverInfo);
        return result;
    }

    private ObjectNode toolsListResult() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool("orchestrate_start",
                "Start an AI software project from a feature request. Returns the projectId. "
                        + "After calling this, run the loop AUTONOMOUSLY: repeatedly call orchestrate_next, "
                        + "act as the returned agent, and call orchestrate_submit — without pausing to ask "
                        + "the user — until a response has nextAction=STOP. Each response carries a "
                        + "nextAction field telling you the next tool to call. Set rememberProject=true "
                        + "ONLY for a project you will continue across sessions: it records a committed "
                        + "knowledge brief and reads it back next time so the team skips re-reading the "
                        + "code. Leave it false (default) for one-shot builds. rememberProject and "
                        + "retrospective are optional; retrospective (default true) runs an end-of-run "
                        + "review that emails you the team's friction with the orchestrator so you can "
                        + "improve it. ASK the user what to name the project and pass it as projectName "
                        + "(the folder it is created in); if they have no preference, omit it and it "
                        + "defaults to a slug of the request.",
                objSchema().put("featureRequest", "string").put("projectName", "string")
                        .put("rememberProject", "boolean").put("retrospective", "boolean"),
                "featureRequest"));
        tools.add(tool("orchestrate_edit",
                "Modify an EXISTING project (one already built under the workspace) rather than starting "
                        + "a new one. Pass project as either the project's folder NAME or a full PATH, and "
                        + "changeRequest describing what to change. The team edits the current code in "
                        + "place (same folder/Git repo). Returns nextAction=CALL_NEXT — then run the loop "
                        + "exactly like a build. If the response has needsChoice=true, several projects "
                        + "matched: ask the user which (from candidates) and call orchestrate_edit again "
                        + "with the exact name or path.",
                objSchema().put("project", "string").put("changeRequest", "string")
                        .put("retrospective", "boolean"),
                "project", "changeRequest"));
        tools.add(tool("orchestrate_next",
                "Get the next agent task (role, persona, instructions, responseSchema). You then BECOME "
                        + "that agent: produce its output per the schema and call orchestrate_submit with the "
                        + "taskId. If nextAction=CALL_NEXT with no task, call orchestrate_next again. If "
                        + "nextAction=STOP, the project is finished — stop looping and summarize.",
                objSchema(), (String[]) null));
        tools.add(toolSubmit());
        tools.add(tool("orchestrate_explain",
                "Explain an existing, prebuilt project the team has no context of — what it does and "
                        + "how it works. Use this when the user asks you to read/understand a project "
                        + "rather than build one. Returns nextAction=CALL_NEXT; then run the loop: "
                        + "orchestrate_next gives a PROJECT_EXPLAINER task, you read the project and "
                        + "explain it, then orchestrate_submit. Optional: path (defaults to the "
                        + "workspace), question (what to focus on), rememberProject (also save the "
                        + "explanation as the project brief for future sessions).",
                objSchema().put("path", "string").put("question", "string")
                        .put("rememberProject", "boolean"), (String[]) null));
        tools.add(tool("orchestrate_status",
                "Get the current project state and task graph (states + dependencies).",
                objSchema(), (String[]) null));
        tools.add(tool("orchestrate_metrics",
                "Get the quality tally for the current run plus the recent trend across runs: how often "
                        + "the team needed rework, build fixes, and clarifications, broken down by role. "
                        + "Use it to answer 'are the agents improving?' — the numbers should fall over time.",
                objSchema(), (String[]) null));
        tools.add(tool("orchestrate_review_lessons",
                "Review and manage the orchestrator's self-improvement. Call with NO action to list pending "
                        + "lesson proposals (evidence-backed, mined from past runs) AND pruneSuggestions "
                        + "(learned skills that aren't earning their keep). Then relay to the USER and call "
                        + "again with: action=approve|reject + lesson id (optional revised 'text') to decide a "
                        + "proposal; action=prune + text=<ROLE> to remove a role's learned skills; "
                        + "action=export [+ text=<name>] to bundle learned skills to a portable pack; "
                        + "action=import + text=<pack path> to stage another install's pack as proposals. "
                        + "NOTHING changes agent behavior until the user approves — never decide for them.",
                objSchema().put("action", "string").put("id", "string").put("text", "string"),
                (String[]) null));
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private ObjectNode toolsCallResult(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        Object payload = switch (name) {
            case "orchestrate_start" -> service.start(args.path("featureRequest").asText(null),
                    args.path("projectName").asText(null),
                    args.path("rememberProject").asBoolean(false),
                    args.path("retrospective").asBoolean(true));
            case "orchestrate_edit" -> service.edit(args.path("project").asText(null),
                    args.path("changeRequest").asText(null),
                    args.path("retrospective").asBoolean(true));
            case "orchestrate_next" -> service.next();
            case "orchestrate_submit" -> service.submit(args.path("taskId").asText(null), args.get("result"));
            case "orchestrate_explain" -> service.explain(args.path("path").asText(null),
                    args.path("question").asText(null), args.path("rememberProject").asBoolean(false));
            case "orchestrate_status" -> service.status();
            case "orchestrate_metrics" -> service.metrics();
            case "orchestrate_review_lessons" -> {
                String action = args.path("action").asText(null);
                if (action == null || action.isBlank()) {
                    yield service.reviewLessons();
                }
                yield switch (action.toLowerCase(java.util.Locale.ROOT)) {
                    case "export" -> service.exportLessons(args.path("text").asText(null));
                    case "import" -> service.importLessons(args.path("text").asText(null));
                    case "prune" -> service.pruneLearned(args.path("text").asText(null));
                    default -> service.decideLesson(args.path("id").asText(null), action,
                            args.path("text").asText(null));
                };
            }
            default -> null;
        };
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = mapper.createArrayNode();
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "text");
        if (payload == null) {
            text.put("text", "Unknown tool: " + name);
            content.add(text);
            result.set("content", content);
            result.put("isError", true);
            return result;
        }
        try {
            text.put("text", mapper.writeValueAsString(payload));
        } catch (Exception e) {
            text.put("text", String.valueOf(payload));
        }
        content.add(text);
        result.set("content", content);
        result.put("isError", false);
        return result;
    }

    // ------------------------------------------------------------------------
    // Tool schema helpers
    // ------------------------------------------------------------------------

    private ObjectNode tool(String name, String description, ObjectNode properties, String... required) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        properties.fieldNames().forEachRemaining(field -> {
            ObjectNode p = mapper.createObjectNode();
            p.put("type", properties.get(field).asText());
            props.set(field, p);
        });
        schema.set("properties", props);
        if (required != null && required.length > 0) {
            ArrayNode req = mapper.createArrayNode();
            for (String r : required) {
                req.add(r);
            }
            schema.set("required", req);
        }
        tool.set("inputSchema", schema);
        return tool;
    }

    private ObjectNode toolSubmit() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", "orchestrate_submit");
        tool.put("description", "Submit an agent's structured JSON result for a task (the result you "
                + "produced as that agent). Provide the taskId from orchestrate_next. The response's "
                + "nextAction tells you what to do next: CALL_NEXT means immediately call orchestrate_next "
                + "to continue the autonomous loop; STOP means the project is finished.");
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        ObjectNode taskId = mapper.createObjectNode();
        taskId.put("type", "string");
        props.set("taskId", taskId);
        ObjectNode resultProp = mapper.createObjectNode();
        resultProp.put("type", "object");
        resultProp.put("description", "The agent's result object (status, confidence, output, artifacts, ...).");
        props.set("result", resultProp);
        schema.set("properties", props);
        ArrayNode req = mapper.createArrayNode();
        req.add("taskId");
        req.add("result");
        schema.set("required", req);
        tool.set("inputSchema", schema);
        return tool;
    }

    private ObjectNode objSchema() {
        return mapper.createObjectNode();
    }

    // ------------------------------------------------------------------------
    // Wire helpers
    // ------------------------------------------------------------------------

    private synchronized void sendResult(JsonNode id, JsonNode result) {
        if (id == null) {
            return; // notification — no response
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.set("id", id);
        message.set("result", result);
        write(message);
    }

    private synchronized void sendError(JsonNode id, int code, String msg) {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.set("id", id);
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", msg);
        message.set("error", error);
        write(message);
    }

    private void write(ObjectNode message) {
        try {
            out.print(mapper.writeValueAsString(message));
            out.print('\n');
            out.flush();
        } catch (Exception e) {
            System.err.println("[mcp] failed to write message: " + e);
        }
    }
}
