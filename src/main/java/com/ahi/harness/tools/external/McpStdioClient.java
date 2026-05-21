package com.ahi.harness.tools.external;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.config.HarnessSettings;
import com.ahi.harness.process.Utf8Process;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class McpStdioClient {
    private final File workspace;
    private final HarnessSettings.ExternalToolServer server;
    private final ConsoleLog log;
    private final ObjectMapper mapper = new ObjectMapper();
    private Process process;
    private Writer writer;
    private BufferedReader reader;
    private boolean initialized;
    private int nextId = 2;

    public McpStdioClient(File workspace, HarnessSettings.ExternalToolServer server, ConsoleLog log) {
        this.workspace = workspace;
        this.server = server;
        this.log = log;
    }

    public JsonNode listTools() throws Exception {
        return sendRequest("tools/list", mapper.createObjectNode()).path("result");
    }

    public JsonNode callTool(String name, JsonNode arguments) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return sendRequest("tools/call", params).path("result");
    }

    public JsonNode listResources() throws Exception {
        return sendRequest("resources/list", mapper.createObjectNode()).path("result");
    }

    public JsonNode readResource(String uri) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", uri);
        return sendRequest("resources/read", params).path("result");
    }

    public JsonNode listPrompts() throws Exception {
        return sendRequest("prompts/list", mapper.createObjectNode()).path("result");
    }

    public JsonNode getPrompt(String name, JsonNode arguments) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return sendRequest("prompts/get", params).path("result");
    }

    public synchronized void close() {
        initialized = false;
        closeQuietly(writer);
        writer = null;
        reader = null;
        if (process != null) {
            process.destroy();
            process = null;
        }
    }

    private synchronized JsonNode sendRequest(String method, ObjectNode params) throws Exception {
        ensureStarted();
        ObjectNode request = request(nextId++, method);
        request.set("params", params);
        logStdioRequest(method, request);
        writeLine(writer, request);
        JsonNode response = readJsonRpcResponse(reader, request.path("id").asInt());
        logStdioResponse(method, response);
        if (response.has("error")) {
            throw new IllegalStateException("MCP stdio request failed for " + server.name()
                    + " method=" + method + ": " + response.path("error").toString());
        }
        return response;
    }

    private void ensureStarted() throws Exception {
        if (initialized && process != null && process.isAlive()) {
            return;
        }
        close();

        List<String> command = new ArrayList<String>();
        command.add(server.command());
        command.addAll(server.args());
        log.info("EXTERNAL", "MCP stdio start " + server.name() + " -> " + command);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace);
        Utf8Process.apply(builder);
        builder.redirectErrorStream(true);
        process = builder.start();

        writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        ObjectNode initialize = initializeRequest();
        logStdioRequest("initialize", initialize);
        writeLine(writer, initialize);
        JsonNode initializeResponse = readJsonRpcResponse(reader, 1);
        logStdioResponse("initialize", initializeResponse);
        if (initializeResponse.has("error")) {
            throw new IllegalStateException("MCP initialize failed: " + initializeResponse.path("error").toString());
        }

        ObjectNode initializedNotification = initializedNotification();
        logStdioRequest("notifications/initialized", initializedNotification);
        writeLine(writer, initializedNotification);
        initialized = true;
        nextId = 2;
    }

    private void logStdioRequest(String method, JsonNode body) throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("transport", "stdio");
        envelope.put("server", server.name());
        envelope.put("command", server.command());
        envelope.set("args", mapper.valueToTree(server.args()));
        envelope.set("body", body);
        log.block("MCP STDIO REQUEST", method + " \u8bf7\u6c42\u4f53 / Request Body", pretty(envelope));
    }

    private void logStdioResponse(String method, JsonNode body) throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("transport", "stdio");
        envelope.put("server", server.name());
        envelope.set("body", body);
        log.block("MCP STDIO RESPONSE", method + " \u54cd\u5e94\u4f53 / Response Body", pretty(envelope));
    }

    private ObjectNode initializeRequest() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2025-11-25");
        ObjectNode capabilities = mapper.createObjectNode();
        params.set("capabilities", capabilities);
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "HarnessStu");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);

        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "initialize");
        request.set("params", params);
        return request;
    }

    private ObjectNode request(int id, String method) {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        return request;
    }

    private ObjectNode initializedNotification() {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        notification.set("params", mapper.createObjectNode());
        return notification;
    }

    private void writeLine(Writer writer, JsonNode node) throws Exception {
        writer.write(mapper.writeValueAsString(node));
        writer.write("\n");
        writer.flush();
    }

    private JsonNode readJsonRpcResponse(BufferedReader reader, int expectedId) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            JsonNode node;
            try {
                node = mapper.readTree(line);
            } catch (Exception e) {
                log.info("EXTERNAL", "MCP stdio non-json line from " + server.name() + ": " + line);
                continue;
            }
            if (node.has("id") && node.path("id").asInt() == expectedId) {
                return node;
            }
        }
        throw new IllegalStateException("MCP stdio server closed before response id=" + expectedId);
    }

    private void closeQuietly(Writer writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (Exception ignored) {
        }
    }

    private String pretty(JsonNode node) throws Exception {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }
}
