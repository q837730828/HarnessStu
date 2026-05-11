package com.ahi.harness.tools.external;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.config.HarnessSettings;
import com.ahi.harness.tools.Tool;
import com.ahi.harness.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;

public class ExternalStdioTool implements Tool {
    private final File workspace;
    private final HarnessSettings.ExternalToolServer server;
    private final String externalName;
    private final String exposedName;
    private final String description;
    private final ObjectNode parameters;
    private final ConsoleLog log;
    private final McpServerManager mcpManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExternalStdioTool(File workspace,
                             HarnessSettings.ExternalToolServer server,
                             String externalName,
                             String exposedName,
                             String description,
                             ObjectNode parameters,
                             ConsoleLog log) {
        this(workspace, server, externalName, exposedName, description, parameters, log, null);
    }

    public ExternalStdioTool(File workspace,
                             HarnessSettings.ExternalToolServer server,
                             String externalName,
                             String exposedName,
                             String description,
                             ObjectNode parameters,
                             ConsoleLog log,
                             McpServerManager mcpManager) {
        this.workspace = workspace;
        this.server = server;
        this.externalName = externalName;
        this.exposedName = exposedName;
        this.description = description;
        this.parameters = parameters;
        this.log = log;
        this.mcpManager = mcpManager;
    }

    @Override
    public String name() {
        return exposedName;
    }

    @Override
    public String description() {
        return "[external:" + server.name() + "] " + description;
    }

    @Override
    public ObjectNode parameters() {
        return parameters;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        log.info("EXTERNAL", "call " + exposedName + " -> " + server.command() + " " + server.args());
        if ("streamable_http".equalsIgnoreCase(server.type())) {
            JsonNode result = mcpManager == null
                    ? new McpStreamableHttpClient(server, log).callTool(externalName, arguments)
                    : mcpManager.callTool(server, externalName, arguments);
            if (result.has("isError") && result.path("isError").asBoolean(false)) {
                return ToolResult.failure(extractMcpContent(result));
            }
            return ToolResult.success(extractMcpContent(result));
        } else if ("mcp".equalsIgnoreCase(server.protocol())) {
            JsonNode result = mcpManager == null
                    ? new McpStdioClient(workspace, server, log).callTool(externalName, arguments)
                    : mcpManager.callTool(server, externalName, arguments);
            if (result.has("isError") && result.path("isError").asBoolean(false)) {
                return ToolResult.failure(extractMcpContent(result));
            }
            return ToolResult.success(extractMcpContent(result));
        } else {
            ObjectNode request = mapper.createObjectNode();
            request.put("type", "call_tool");
            request.put("name", externalName);
            request.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
            JsonNode response = new ExternalStdioClient(workspace, server).request(request, 60);
            boolean ok = response.path("ok").asBoolean(false);
            String content = response.path("content").asText(response.toString());
            return ok ? ToolResult.success(content) : ToolResult.failure(content);
        }
    }

    private String extractMcpContent(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isArray() && content.size() > 0) {
            StringBuilder out = new StringBuilder();
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText())) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(item.path("text").asText());
                }
            }
            if (out.length() > 0) {
                return out.toString();
            }
        }
        return result.toString();
    }
}
