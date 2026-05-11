package com.ahi.harness.tools.provider;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.config.HarnessSettings;
import com.ahi.harness.tools.Tool;
import com.ahi.harness.tools.external.ExternalStdioClient;
import com.ahi.harness.tools.external.ExternalStdioTool;
import com.ahi.harness.tools.external.McpServerManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ExternalStdioToolProvider implements ToolProvider {
    private final File workspace;
    private final HarnessSettings.ExternalToolServer server;
    private final ConsoleLog log;
    private final McpServerManager mcpManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExternalStdioToolProvider(File workspace, HarnessSettings.ExternalToolServer server, ConsoleLog log) {
        this(workspace, server, log, null);
    }

    public ExternalStdioToolProvider(File workspace,
                                     HarnessSettings.ExternalToolServer server,
                                     ConsoleLog log,
                                     McpServerManager mcpManager) {
        this.workspace = workspace;
        this.server = server;
        this.log = log;
        this.mcpManager = mcpManager;
    }

    @Override
    public List<Tool> loadTools() throws Exception {
        List<Tool> tools = new ArrayList<Tool>();
        JsonNode toolDefs;
        if ("streamable_http".equalsIgnoreCase(server.type())) {
            if (mcpManager != null) {
                return mcpManager.loadTools(server);
            }
            throw new IllegalStateException("MCP manager is required for streamable_http server: " + server.name());
        } else if ("mcp".equalsIgnoreCase(server.protocol())) {
            if (mcpManager != null) {
                return mcpManager.loadTools(server);
            }
            throw new IllegalStateException("MCP manager is required for MCP stdio server: " + server.name());
        } else {
            ObjectNode request = mapper.createObjectNode();
            request.put("type", "list_tools");
            JsonNode response = new ExternalStdioClient(workspace, server).request(request, 30);
            toolDefs = response.path("tools");
        }
        if (!toolDefs.isArray()) {
            log.error("EXTERNAL", "No tools array returned by " + server.name());
            return tools;
        }
        for (JsonNode tool : toolDefs) {
            String externalName = tool.path("name").asText();
            if (externalName.trim().isEmpty()) {
                continue;
            }
            String exposedName = "external__" + sanitize(server.name()) + "__" + sanitize(externalName);
            String description = tool.path("description").asText("External tool " + externalName);
            JsonNode params = tool.has("inputSchema") ? tool.path("inputSchema") : tool.path("parameters");
            ObjectNode parameters = params.isObject() ? (ObjectNode) params : mapper.createObjectNode();
            tools.add(new ExternalStdioTool(workspace, server, externalName, exposedName, description, parameters, log));
            log.info("EXTERNAL", "loaded " + exposedName);
        }
        return tools;
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
