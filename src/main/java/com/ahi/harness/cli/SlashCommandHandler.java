package com.ahi.harness.cli;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.config.HarnessSettings;
import com.ahi.harness.core.Conversation;
import com.ahi.harness.core.Message;
import com.ahi.harness.process.Utf8Process;
import com.ahi.harness.session.JsonlSessionStore;
import com.ahi.harness.tools.Tool;
import com.ahi.harness.tools.ToolRegistry;
import com.ahi.harness.tools.external.McpServerManager;
import com.ahi.harness.tools.provider.ExternalStdioToolProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SlashCommandHandler {
    private final File workspace;
    private final File sessionDirectory;
    private final ToolRegistry tools;
    private final ConsoleLog log;
    private final int compactKeepRecentMessages;
    private final List<HarnessSettings.ExternalToolServer> externalServers;
    private final McpServerManager mcpManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public SlashCommandHandler(File workspace,
                               File sessionDirectory,
                               ToolRegistry tools,
                               ConsoleLog log,
                               int compactKeepRecentMessages,
                               List<HarnessSettings.ExternalToolServer> externalServers,
                               McpServerManager mcpManager) {
        this.workspace = workspace;
        this.sessionDirectory = sessionDirectory;
        this.tools = tools;
        this.log = log;
        this.compactKeepRecentMessages = compactKeepRecentMessages;
        this.externalServers = externalServers;
        this.mcpManager = mcpManager;
    }

    public boolean handle(String line, Conversation conversation) throws Exception {
        String trimmed = line.trim();
        if (!trimmed.startsWith("/")) {
            return false;
        }
        if ("/help".equals(trimmed)) {
            log.block("HARNESS", "Slash commands", "/help\n/tools\n/status\n/diff\n/sessions\n/resume latest\n/resume <session-file>\n/compact\n/reload\n/mcp/status\n/mcp/reload <server|all>\n/mcp/resources [server]\n/mcp/read <server> <uri>\n/mcp/prompts [server]\n/mcp/get-prompt <server> <name> [json-args]\n/exit");
            return true;
        }
        if ("/tools".equals(trimmed)) {
            StringBuilder out = new StringBuilder();
            for (Tool tool : tools.all()) {
                out.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
            }
            log.block("HARNESS", "Available tools", out.toString());
            return true;
        }
        if ("/status".equals(trimmed)) {
            log.info("HARNESS", "workspace=" + workspace.getAbsolutePath()
                    + ", messages=" + conversation.size()
                    + ", sessions=" + JsonlSessionStore.listSessions(sessionDirectory).size());
            log.block("EXTERNAL", "MCP status", mcpManager.statusReport());
            return true;
        }
        if ("/diff".equals(trimmed)) {
            log.block("HARNESS", "git diff", runGitDiff());
            return true;
        }
        if ("/sessions".equals(trimmed)) {
            List<File> sessions = JsonlSessionStore.listSessions(sessionDirectory);
            StringBuilder out = new StringBuilder();
            for (File session : sessions) {
                out.append(session.getName()).append('\n');
            }
            log.block("HARNESS", "Sessions", out.length() == 0 ? "No sessions found." : out.toString());
            return true;
        }
        if (trimmed.startsWith("/resume")) {
            File session = resolveSession(trimmed);
            if (session == null || !session.exists()) {
                log.error("HARNESS", "Session not found. Use /sessions first.");
                return true;
            }
            List<Message> loaded = JsonlSessionStore.loadMessages(session);
            if (loaded.isEmpty()) {
                log.error("HARNESS", "No resumable messages in " + session.getName());
                return true;
            }
            List<Message> merged = new ArrayList<Message>();
            for (Message message : conversation.messages()) {
                if ("system".equals(message.role())) {
                    merged.add(message);
                }
            }
            for (Message message : loaded) {
                if (!"system".equals(message.role())) {
                    merged.add(message);
                }
            }
            conversation.replaceMessages(merged);
            log.info("HARNESS", "Resumed " + loaded.size() + " message(s) from " + session.getName());
            return true;
        }
        if ("/compact".equals(trimmed)) {
            int before = conversation.size();
            conversation.compact(compactKeepRecentMessages);
            log.info("HARNESS", "Compacted conversation: " + before + " -> " + conversation.size() + " messages");
            return true;
        }
        if ("/reload".equals(trimmed)) {
            int added = 0;
            int failed = 0;
            for (HarnessSettings.ExternalToolServer server : externalServers) {
                try {
                    if (mcpManager.isMcp(server)) {
                        removeExternalTools(server);
                    }
                    List<Tool> newTools = new ExternalStdioToolProvider(workspace, server, log, mcpManager).loadTools();
                    for (Tool tool : newTools) {
                        tools.register(tool);
                        log.info("HARNESS", "re-registered tool: " + tool.name());
                        added++;
                    }
                } catch (Exception e) {
                    log.error("EXTERNAL", "Failed to reload " + server.name() + ": " + e.getMessage());
                    failed++;
                }
            }
            log.info("HARNESS", "Reload complete: " + added + " tools loaded, " + failed + " servers failed");
            return true;
        }
        if ("/mcp/status".equals(trimmed)) {
            log.block("EXTERNAL", "MCP status", mcpManager.statusReport());
            return true;
        }
        if (trimmed.startsWith("/mcp/reload")) {
            String name = optionalArg(trimmed, "/mcp/reload");
            if (name.trim().isEmpty()) {
                log.error("HARNESS", "Usage: /mcp/reload <server|all>");
                return true;
            }
            if ("all".equalsIgnoreCase(name)) {
                int count = 0;
                for (HarnessSettings.ExternalToolServer server : externalServers) {
                    if (!mcpManager.isMcp(server)) {
                        continue;
                    }
                    count += reloadMcpServer(server);
                }
                log.info("EXTERNAL", "MCP reload all complete: " + count + " tool(s) loaded");
                return true;
            }
            HarnessSettings.ExternalToolServer server = resolveMcpServer(name);
            if (server != null) {
                int count = reloadMcpServer(server);
                log.info("EXTERNAL", "MCP reload complete: " + server.name() + ", tools=" + count);
            }
            return true;
        }
        if (trimmed.startsWith("/mcp/resources")) {
            HarnessSettings.ExternalToolServer server = resolveMcpServer(optionalArg(trimmed, "/mcp/resources"));
            if (server != null) {
                log.block("EXTERNAL", "MCP resources", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(listResources(server)));
            }
            return true;
        }
        if (trimmed.startsWith("/mcp/read ")) {
            String[] parts = trimmed.split("\\s+", 3);
            if (parts.length < 3) {
                log.error("HARNESS", "Usage: /mcp/read <server> <uri>");
                return true;
            }
            HarnessSettings.ExternalToolServer server = resolveMcpServer(parts[1]);
            if (server != null) {
                log.block("EXTERNAL", "MCP resource", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(readResource(server, parts[2])));
            }
            return true;
        }
        if (trimmed.startsWith("/mcp/prompts")) {
            HarnessSettings.ExternalToolServer server = resolveMcpServer(optionalArg(trimmed, "/mcp/prompts"));
            if (server != null) {
                log.block("EXTERNAL", "MCP prompts", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(listPrompts(server)));
            }
            return true;
        }
        if (trimmed.startsWith("/mcp/get-prompt ")) {
            String[] parts = trimmed.split("\\s+", 4);
            if (parts.length < 3) {
                log.error("HARNESS", "Usage: /mcp/get-prompt <server> <name> [json-args]");
                return true;
            }
            HarnessSettings.ExternalToolServer server = resolveMcpServer(parts[1]);
            JsonNode args;
            try {
                args = parts.length >= 4 ? mapper.readTree(parts[3]) : mapper.createObjectNode();
            } catch (Exception e) {
                log.error("HARNESS", "Invalid JSON args. Example: /mcp/get-prompt demo demo_review {\"topic\":\"MCP harness\"}");
                return true;
            }
            if (server != null) {
                log.block("EXTERNAL", "MCP prompt", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(getPrompt(server, parts[2], args)));
            }
            return true;
        }
        return false;
    }

    private String optionalArg(String command, String prefix) {
        String value = command.substring(prefix.length()).trim();
        return value.isEmpty() ? "" : value;
    }

    private HarnessSettings.ExternalToolServer resolveMcpServer(String name) {
        HarnessSettings.ExternalToolServer server = mcpManager.resolve(name);
        if (server != null) {
            return server;
        }
        log.error("HARNESS", "MCP server not found: " + name);
        return null;
    }

    private JsonNode listResources(HarnessSettings.ExternalToolServer server) throws Exception {
        return mcpManager.listResources(server);
    }

    private JsonNode readResource(HarnessSettings.ExternalToolServer server, String uri) throws Exception {
        return mcpManager.readResource(server, uri);
    }

    private JsonNode listPrompts(HarnessSettings.ExternalToolServer server) throws Exception {
        return mcpManager.listPrompts(server);
    }

    private JsonNode getPrompt(HarnessSettings.ExternalToolServer server, String name, JsonNode args) throws Exception {
        return mcpManager.getPrompt(server, name, args);
    }

    private int reloadMcpServer(HarnessSettings.ExternalToolServer server) throws Exception {
        removeExternalTools(server);
        List<Tool> loaded = mcpManager.reload(server.name());
        for (Tool tool : loaded) {
            tools.register(tool);
            log.info("HARNESS", "re-registered tool: " + tool.name());
        }
        return loaded.size();
    }

    private void removeExternalTools(HarnessSettings.ExternalToolServer server) {
        int removed = tools.unregisterPrefix("external__" + sanitize(server.name()) + "__");
        if (removed > 0) {
            log.info("HARNESS", "removed " + removed + " cached tool(s) for " + server.name());
        }
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private File resolveSession(String command) {
        String[] parts = command.split("\\s+", 2);
        if (parts.length == 1 || "latest".equalsIgnoreCase(parts[1].trim())) {
            return JsonlSessionStore.latestSession(sessionDirectory);
        }
        return new File(sessionDirectory, parts[1].trim());
    }

    private String runGitDiff() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("git", "diff");
        builder.directory(workspace);
        Utf8Process.apply(builder);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (out.length() < 12000) {
                out.append(line).append('\n');
            }
        }
        int exit = process.waitFor();
        if (out.length() == 0) {
            out.append("No diff. exit_code=").append(exit);
        }
        return out.toString();
    }
}
