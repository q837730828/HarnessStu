package com.ahi.harness.tools.external;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.config.HarnessSettings;
import com.ahi.harness.tools.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpServerManager {
    private final File workspace;
    private final List<HarnessSettings.ExternalToolServer> servers;
    private final ConsoleLog log;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, McpStdioClient> stdioClients = new LinkedHashMap<String, McpStdioClient>();
    private final Map<String, McpStreamableHttpClient> httpClients = new LinkedHashMap<String, McpStreamableHttpClient>();
    private final Map<String, List<Tool>> toolCache = new LinkedHashMap<String, List<Tool>>();
    private final Map<String, ServerState> states = new LinkedHashMap<String, ServerState>();

    public McpServerManager(File workspace, List<HarnessSettings.ExternalToolServer> servers, ConsoleLog log) {
        this.workspace = workspace;
        this.servers = servers;
        this.log = log;
        for (HarnessSettings.ExternalToolServer server : servers) {
            if (isMcp(server)) {
                states.put(server.name(), new ServerState(server));
            }
        }
    }

    public boolean isMcp(HarnessSettings.ExternalToolServer server) {
        return "streamable_http".equalsIgnoreCase(server.type()) || "mcp".equalsIgnoreCase(server.protocol());
    }

    public List<Tool> loadTools(HarnessSettings.ExternalToolServer server) throws Exception {
        if (!isMcp(server)) {
            throw new IllegalArgumentException("Not an MCP server: " + server.name());
        }
        List<Tool> cached = toolCache.get(server.name());
        if (cached != null) {
            markConnected(server.name(), cached.size());
            return cached;
        }
        return reload(server.name());
    }

    public List<Tool> reload(String serverName) throws Exception {
        HarnessSettings.ExternalToolServer server = server(serverName);
        if (!isMcp(server)) {
            throw new IllegalArgumentException("Not an MCP server: " + serverName);
        }
        close(serverName);
        JsonNode toolDefs = clientListTools(server).path("tools");
        List<Tool> tools = new ArrayList<Tool>();
        if (!toolDefs.isArray()) {
            markFailed(serverName, "No tools array returned.");
            log.error("EXTERNAL", "No tools array returned by " + serverName);
            return tools;
        }
        for (JsonNode tool : toolDefs) {
            String externalName = tool.path("name").asText();
            if (externalName.trim().isEmpty()) {
                continue;
            }
            String exposedName = exposedName(server.name(), externalName);
            String description = tool.path("description").asText("External tool " + externalName);
            JsonNode params = tool.has("inputSchema") ? tool.path("inputSchema") : tool.path("parameters");
            ObjectNode parameters = params.isObject() ? (ObjectNode) params : mapper.createObjectNode();
            tools.add(new ExternalStdioTool(workspace, server, externalName, exposedName, description, parameters, log, this));
            log.info("EXTERNAL", "loaded " + exposedName + " from MCP manager");
        }
        toolCache.put(serverName, tools);
        markConnected(serverName, tools.size());
        state(serverName).reloads++;
        return tools;
    }

    public List<Tool> reloadAll() throws Exception {
        List<Tool> all = new ArrayList<Tool>();
        for (HarnessSettings.ExternalToolServer server : servers) {
            if (isMcp(server)) {
                all.addAll(reload(server.name()));
            }
        }
        return all;
    }

    public JsonNode callTool(HarnessSettings.ExternalToolServer server, String name, JsonNode arguments) throws Exception {
        try {
            JsonNode result;
            if ("streamable_http".equalsIgnoreCase(server.type())) {
                result = httpClient(server).callTool(name, arguments);
            } else {
                result = stdioClient(server).callTool(name, arguments);
            }
            markConnected(server.name(), cachedToolCount(server.name()));
            return result;
        } catch (Exception e) {
            markFailed(server.name(), e.getMessage());
            throw e;
        }
    }

    public JsonNode listResources(HarnessSettings.ExternalToolServer server) throws Exception {
        try {
            JsonNode result = "streamable_http".equalsIgnoreCase(server.type())
                    ? httpClient(server).listResources()
                    : stdioClient(server).listResources();
            state(server.name()).resources = result.path("resources").isArray() ? result.path("resources").size() : -1;
            markConnected(server.name(), cachedToolCount(server.name()));
            return result;
        } catch (Exception e) {
            markFailed(server.name(), e.getMessage());
            throw e;
        }
    }

    public JsonNode readResource(HarnessSettings.ExternalToolServer server, String uri) throws Exception {
        try {
            JsonNode result = "streamable_http".equalsIgnoreCase(server.type())
                    ? httpClient(server).readResource(uri)
                    : stdioClient(server).readResource(uri);
            markConnected(server.name(), cachedToolCount(server.name()));
            return result;
        } catch (Exception e) {
            markFailed(server.name(), e.getMessage());
            throw e;
        }
    }

    public JsonNode listPrompts(HarnessSettings.ExternalToolServer server) throws Exception {
        try {
            JsonNode result = "streamable_http".equalsIgnoreCase(server.type())
                    ? httpClient(server).listPrompts()
                    : stdioClient(server).listPrompts();
            state(server.name()).prompts = result.path("prompts").isArray() ? result.path("prompts").size() : -1;
            markConnected(server.name(), cachedToolCount(server.name()));
            return result;
        } catch (Exception e) {
            markFailed(server.name(), e.getMessage());
            throw e;
        }
    }

    public JsonNode getPrompt(HarnessSettings.ExternalToolServer server, String name, JsonNode args) throws Exception {
        try {
            JsonNode result = "streamable_http".equalsIgnoreCase(server.type())
                    ? httpClient(server).getPrompt(name, args)
                    : stdioClient(server).getPrompt(name, args);
            markConnected(server.name(), cachedToolCount(server.name()));
            return result;
        } catch (Exception e) {
            markFailed(server.name(), e.getMessage());
            throw e;
        }
    }

    public String statusReport() {
        StringBuilder out = new StringBuilder();
        for (ServerState state : states.values()) {
            out.append("- ").append(state.name)
                    .append(" type=").append(state.type)
                    .append(" protocol=").append(state.protocol)
                    .append(" connected=").append(state.connected)
                    .append(" tools=").append(value(state.tools))
                    .append(" resources=").append(value(state.resources))
                    .append(" prompts=").append(value(state.prompts))
                    .append(" reloads=").append(state.reloads);
            if (state.lastError != null && !state.lastError.isEmpty()) {
                out.append(" last_error=").append(state.lastError);
            }
            out.append('\n');
        }
        return out.length() == 0 ? "No MCP servers configured." : out.toString();
    }

    public HarnessSettings.ExternalToolServer resolve(String name) {
        HarnessSettings.ExternalToolServer first = null;
        for (HarnessSettings.ExternalToolServer server : servers) {
            if (!isMcp(server)) {
                continue;
            }
            if (first == null) {
                first = server;
            }
            if (server.name().equals(name)) {
                return server;
            }
        }
        if (name == null || name.trim().isEmpty()) {
            return first;
        }
        return null;
    }

    public void closeAll() {
        for (String name : new ArrayList<String>(stdioClients.keySet())) {
            close(name);
        }
        httpClients.clear();
    }

    private JsonNode clientListTools(HarnessSettings.ExternalToolServer server) throws Exception {
        try {
            JsonNode result = "streamable_http".equalsIgnoreCase(server.type())
                    ? httpClient(server).listTools()
                    : stdioClient(server).listTools();
            markConnected(server.name(), result.path("tools").isArray() ? result.path("tools").size() : -1);
            return result;
        } catch (Exception e) {
            markFailed(server.name(), e.getMessage());
            throw e;
        }
    }

    private McpStdioClient stdioClient(HarnessSettings.ExternalToolServer server) {
        McpStdioClient client = stdioClients.get(server.name());
        if (client == null) {
            client = new McpStdioClient(workspace, server, log);
            stdioClients.put(server.name(), client);
        }
        return client;
    }

    private McpStreamableHttpClient httpClient(HarnessSettings.ExternalToolServer server) {
        McpStreamableHttpClient client = httpClients.get(server.name());
        if (client == null) {
            client = new McpStreamableHttpClient(server, log);
            httpClients.put(server.name(), client);
        }
        return client;
    }

    private void close(String serverName) {
        McpStdioClient stdio = stdioClients.remove(serverName);
        if (stdio != null) {
            stdio.close();
        }
        httpClients.remove(serverName);
        toolCache.remove(serverName);
    }

    private HarnessSettings.ExternalToolServer server(String name) {
        HarnessSettings.ExternalToolServer server = resolve(name);
        if (server == null) {
            throw new IllegalArgumentException("MCP server not found: " + name);
        }
        return server;
    }

    private String exposedName(String serverName, String toolName) {
        return "external__" + sanitize(serverName) + "__" + sanitize(toolName);
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private ServerState state(String name) {
        ServerState state = states.get(name);
        if (state == null) {
            HarnessSettings.ExternalToolServer server = server(name);
            state = new ServerState(server);
            states.put(name, state);
        }
        return state;
    }

    private void markConnected(String name, int toolCount) {
        ServerState state = state(name);
        state.connected = true;
        state.lastError = "";
        if (toolCount >= 0) {
            state.tools = toolCount;
        }
    }

    private void markFailed(String name, String error) {
        ServerState state = state(name);
        state.connected = false;
        state.lastError = error == null ? "" : error;
    }

    private int cachedToolCount(String name) {
        List<Tool> tools = toolCache.get(name);
        return tools == null ? -1 : tools.size();
    }

    private String value(int count) {
        return count < 0 ? "unknown" : String.valueOf(count);
    }

    private static class ServerState {
        private final String name;
        private final String type;
        private final String protocol;
        private boolean connected;
        private int tools = -1;
        private int resources = -1;
        private int prompts = -1;
        private int reloads;
        private String lastError = "";

        private ServerState(HarnessSettings.ExternalToolServer server) {
            this.name = server.name();
            this.type = server.type();
            this.protocol = server.protocol();
        }
    }
}
