package com.ahi.harness.tools.external;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.config.HarnessSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class McpStreamableHttpClient {
    private final HarnessSettings.ExternalToolServer server;
    private final ConsoleLog log;
    private final ObjectMapper mapper = new ObjectMapper();
    private String sessionId;
    private int nextId = 2;

    public McpStreamableHttpClient(HarnessSettings.ExternalToolServer server, ConsoleLog log) {
        this.server = server;
        this.log = log;
    }

    public JsonNode listTools() throws Exception {
        initialize();
        ObjectNode request = nextRequest("tools/list");
        request.set("params", mapper.createObjectNode());
        return postJsonRpc(request, true).path("result");
    }

    public JsonNode callTool(String name, JsonNode arguments) throws Exception {
        initialize();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        ObjectNode request = nextRequest("tools/call");
        request.set("params", params);
        return postJsonRpc(request, true).path("result");
    }

    public JsonNode listResources() throws Exception {
        initialize();
        ObjectNode request = nextRequest("resources/list");
        request.set("params", mapper.createObjectNode());
        return postJsonRpc(request, true).path("result");
    }

    public JsonNode readResource(String uri) throws Exception {
        initialize();
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", uri);
        ObjectNode request = nextRequest("resources/read");
        request.set("params", params);
        return postJsonRpc(request, true).path("result");
    }

    public JsonNode listPrompts() throws Exception {
        initialize();
        ObjectNode request = nextRequest("prompts/list");
        request.set("params", mapper.createObjectNode());
        return postJsonRpc(request, true).path("result");
    }

    public JsonNode getPrompt(String name, JsonNode arguments) throws Exception {
        initialize();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        ObjectNode request = nextRequest("prompts/get");
        request.set("params", params);
        return postJsonRpc(request, true).path("result");
    }

    private void initialize() throws Exception {
        if (sessionId != null) {
            return;
        }
        log.info("EXTERNAL", "MCP HTTP initialize -> " + server.url());
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2025-11-25");
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "HarnessStu");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);

        ObjectNode initialize = request(1, "initialize");
        initialize.set("params", params);
        JsonNode response = postJsonRpc(initialize, true);
        if (response.has("error")) {
            throw new IllegalStateException("MCP HTTP initialize failed: " + response.path("error").toString());
        }

        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        notification.set("params", mapper.createObjectNode());
        postJsonRpc(notification, false);
        nextId = 2;
    }

    private synchronized ObjectNode nextRequest(String method) {
        return request(nextId++, method);
    }

    private ObjectNode request(int id, String method) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", id);
        node.put("method", method);
        return node;
    }

    private JsonNode postJsonRpc(JsonNode payload, boolean expectBody) throws Exception {
        String method = payload.path("method").asText("<notification>");
        logHttpRequest(method, payload);
        HttpURLConnection connection = (HttpURLConnection) new URL(server.url()).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            connection.setRequestProperty("Mcp-Session-Id", sessionId);
        }

        OutputStream out = connection.getOutputStream();
        out.write(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        out.close();

        String returnedSessionId = connection.getHeaderField("Mcp-Session-Id");
        if (returnedSessionId != null && !returnedSessionId.trim().isEmpty()) {
            sessionId = returnedSessionId.trim();
        }

        int status = connection.getResponseCode();
        String contentType = connection.getHeaderField("Content-Type");
        if (!expectBody && (status == 200 || status == 202 || status == 204)) {
            logHttpResponse(method, status, contentType, mapper.createObjectNode(), false);
            return mapper.createObjectNode();
        }
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = readAll(stream);
        if (status >= 400) {
            logHttpResponse(method, status, contentType, textBody(body), false);
            throw new IllegalStateException("MCP HTTP error " + status + ": " + body);
        }
        if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
            JsonNode parsed = parseSseJson(body);
            logHttpResponse(method, status, contentType, parsed, true);
            return parsed;
        }
        if (body.trim().isEmpty()) {
            logHttpResponse(method, status, contentType, mapper.createObjectNode(), false);
            return mapper.createObjectNode();
        }
        JsonNode parsed = mapper.readTree(body);
        logHttpResponse(method, status, contentType, parsed, false);
        return parsed;
    }

    private void logHttpRequest(String method, JsonNode payload) throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("transport", "streamable_http");
        envelope.put("server", server.name());

        ObjectNode http = mapper.createObjectNode();
        http.put("method", "POST");
        http.put("url", server.url());
        envelope.set("http", http);

        ObjectNode headers = mapper.createObjectNode();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            headers.put("Mcp-Session-Id", maskSession(sessionId));
        }
        envelope.set("headers", headers);
        envelope.set("body", sanitize(payload));

        log.block("MCP HTTP REQUEST", method + " \u8bf7\u6c42\u4f53 / Request Body", pretty(envelope));
    }

    private void logHttpResponse(String method, int status, String contentType, JsonNode body, boolean sse) throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("transport", "streamable_http");
        envelope.put("server", server.name());
        envelope.put("sse", sse);

        ObjectNode http = mapper.createObjectNode();
        http.put("status", status);
        envelope.set("http", http);

        ObjectNode headers = mapper.createObjectNode();
        headers.put("Content-Type", contentType == null ? "" : contentType);
        headers.put("Mcp-Session-Id", maskSession(sessionId));
        envelope.set("headers", headers);
        envelope.set("body", sanitize(body));

        log.block("MCP HTTP RESPONSE", method + " \u54cd\u5e94\u4f53 / Response Body", pretty(envelope));
    }

    private JsonNode textBody(String body) {
        ObjectNode node = mapper.createObjectNode();
        node.put("raw", trim(body == null ? "" : body, 2000));
        return node;
    }

    private JsonNode sanitize(JsonNode node) {
        if (node == null || node.isNull()) {
            return mapper.getNodeFactory().nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if ("text".equals(key) && value.isTextual()) {
                    out.put(key, trim(value.asText(), 800));
                } else {
                    out.set(key, sanitize(value));
                }
            }
            return out;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode out = mapper.createArrayNode();
            for (JsonNode item : node) {
                out.add(sanitize(item));
            }
            return out;
        }
        if (node.isTextual()) {
            return mapper.getNodeFactory().textNode(trim(node.asText(), 800));
        }
        return node;
    }

    private String trim(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private String pretty(JsonNode node) throws Exception {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    private String maskSession(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return "***";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }

    private JsonNode parseSseJson(String body) throws Exception {
        StringBuilder data = new StringBuilder();
        String[] lines = body.replace("\r\n", "\n").split("\n");
        for (String line : lines) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (data.length() == 0) {
            throw new IllegalStateException("SSE response did not contain data lines.");
        }
        return mapper.readTree(data.toString());
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }
}
