package com.ahi.harness.tools.external;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.config.HarnessSettings;
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
import java.util.concurrent.TimeUnit;

public class McpStdioClient {
    private final File workspace;
    private final HarnessSettings.ExternalToolServer server;
    private final ConsoleLog log;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpStdioClient(File workspace, HarnessSettings.ExternalToolServer server, ConsoleLog log) {
        this.workspace = workspace;
        this.server = server;
        this.log = log;
    }

    public JsonNode listTools() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 2);
        request.put("method", "tools/list");
        request.set("params", mapper.createObjectNode());
        return runSession(request, 30).path("result");
    }

    public JsonNode callTool(String name, JsonNode arguments) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);

        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 2);
        request.put("method", "tools/call");
        request.set("params", params);
        return runSession(request, 60).path("result");
    }

    public JsonNode listResources() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 2);
        request.put("method", "resources/list");
        request.set("params", mapper.createObjectNode());
        return runSession(request, 30).path("result");
    }

    public JsonNode readResource(String uri) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", uri);
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 2);
        request.put("method", "resources/read");
        request.set("params", params);
        return runSession(request, 30).path("result");
    }

    public JsonNode listPrompts() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 2);
        request.put("method", "prompts/list");
        request.set("params", mapper.createObjectNode());
        return runSession(request, 30).path("result");
    }

    public JsonNode getPrompt(String name, JsonNode arguments) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 2);
        request.put("method", "prompts/get");
        request.set("params", params);
        return runSession(request, 30).path("result");
    }

    private JsonNode runSession(ObjectNode secondRequest, int timeoutSeconds) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(server.command());
        command.addAll(server.args());
        log.info("EXTERNAL", "MCP stdio start " + server.name() + " -> " + command);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        ObjectNode initialize = initializeRequest();
        logStdioRequest("initialize", initialize);
        writeLine(writer, initialize);
        JsonNode initializeResponse = readJsonRpcResponse(reader, 1);
        logStdioResponse("initialize", initializeResponse);
        if (initializeResponse.has("error")) {
            throw new IllegalStateException("MCP initialize failed: " + initializeResponse.path("error").toString());
        }

        ObjectNode initialized = initializedNotification();
        logStdioRequest("notifications/initialized", initialized);
        writeLine(writer, initialized);
        logStdioRequest(secondRequest.path("method").asText(), secondRequest);
        writeLine(writer, secondRequest);
        writer.close();

        JsonNode response = readJsonRpcResponse(reader, secondRequest.path("id").asInt());
        logStdioResponse(secondRequest.path("method").asText(), response);
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("MCP stdio server timed out: " + server.name());
        }
        int exit = process.exitValue();
        if (exit != 0) {
            throw new IllegalStateException("MCP stdio server failed. exit_code=" + exit);
        }
        if (response.has("error")) {
            throw new IllegalStateException("MCP request failed: " + response.path("error").toString());
        }
        return response;
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
            JsonNode node = mapper.readTree(line);
            if (node.has("id") && node.path("id").asInt() == expectedId) {
                return node;
            }
        }
        throw new IllegalStateException("MCP stdio server closed before response id=" + expectedId);
    }

    private String pretty(JsonNode node) throws Exception {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }
}
