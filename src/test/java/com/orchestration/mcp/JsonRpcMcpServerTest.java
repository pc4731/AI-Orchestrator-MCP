package com.orchestration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the stdio JSON-RPC framing and the MCP handshake/tool advertisement by feeding the server
 * canned client messages and inspecting the newline-delimited responses. Uses a null service since
 * these methods don't reach it.
 */
class JsonRpcMcpServerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<JsonNode> exchange(String... requests) throws Exception {
        String input = String.join("\n", requests) + "\n";
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new JsonRpcMcpServer(null).serve(in, out);

        List<JsonNode> messages = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                messages.add(mapper.readTree(line));
            }
        }
        return messages;
    }

    @Test
    void initializeReturnsCapabilitiesAndServerInfo() throws Exception {
        List<JsonNode> out = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}");
        assertEquals(1, out.size());
        JsonNode result = out.get(0).get("result");
        assertEquals("2024-11-05", result.get("protocolVersion").asText());
        assertTrue(result.get("capabilities").has("tools"));
        assertEquals("agent-orchestration", result.get("serverInfo").get("name").asText());
    }

    @Test
    void notificationsProduceNoResponse() throws Exception {
        List<JsonNode> out = exchange("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertTrue(out.isEmpty());
    }

    @Test
    void toolsListAdvertisesTheFourOrchestrationTools() throws Exception {
        List<JsonNode> out = exchange("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonNode tools = out.get(0).get("result").get("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.get("name").asText()));
        assertTrue(names.contains("orchestrate_start"));
        assertTrue(names.contains("orchestrate_next"));
        assertTrue(names.contains("orchestrate_submit"));
        assertTrue(names.contains("orchestrate_status"));
    }

    @Test
    void unknownMethodReturnsError() throws Exception {
        List<JsonNode> out = exchange("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"bogus\"}");
        assertEquals(-32601, out.get(0).get("error").get("code").asInt());
    }

    @Test
    void malformedLineIsIgnored() throws Exception {
        List<JsonNode> out = exchange("not json at all");
        assertTrue(out.isEmpty());
    }

    @Test
    void initializeEchoesRequestedProtocolVersion() throws Exception {
        List<JsonNode> out = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
        assertEquals("2025-06-18", out.get(0).get("result").get("protocolVersion").asText());
        assertFalse(out.get(0).has("error"));
    }
}
