package com.ahi.harness.tools;

import com.ahi.harness.ConsoleLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SubagentWriteTool implements Tool {
    private static final int MAX_DESCRIPTION_CHARS = 300;
    private static final int MAX_PROMPT_CHARS = 4000;
    private static final int MAX_STEPS = 16;
    private static final List<String> SAFE_TOOLS = Arrays.asList("list_files", "read_file", "grep");

    private final File workspace;
    private final ConsoleLog log;
    private final ObjectMapper mapper = new ObjectMapper();

    public SubagentWriteTool(File workspace, ConsoleLog log) {
        this.workspace = workspace;
        this.log = log;
    }

    @Override
    public String name() {
        return "subagent_write";
    }

    @Override
    public String description() {
        return "Create or update a project-level read-only subagent definition under .harness/agents.";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = JsonSchemas.object();
        JsonSchemas.addRequired(schema, "name", JsonSchemas.stringProperty("Subagent name. Must match [A-Za-z0-9_-]+."));
        JsonSchemas.addRequired(schema, "description", JsonSchemas.stringProperty("Short description of when this subagent should be used."));
        JsonSchemas.addRequired(schema, "prompt", JsonSchemas.stringProperty("Subagent system prompt body."));

        ObjectNode tools = mapper.createObjectNode();
        tools.put("type", "array");
        tools.put("description", "Optional read-only tools. Allowed: list_files, read_file, grep. Defaults to all three.");
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "string");
        ArrayNode values = mapper.createArrayNode();
        values.add("list_files");
        values.add("read_file");
        values.add("grep");
        item.set("enum", values);
        tools.set("items", item);
        JsonSchemas.addOptional(schema, "tools", tools);
        JsonSchemas.addOptional(schema, "max_steps", JsonSchemas.stringProperty("Optional max steps, clamped to 1..16. Defaults to 8."));
        JsonSchemas.addOptional(schema, "memory", JsonSchemas.stringProperty("Optional true/false. Defaults to true."));
        JsonSchemas.addOptional(schema, "permission_mode", JsonSchemas.stringProperty("Optional subagent permission mode metadata. Defaults to strict."));
        JsonSchemas.addOptional(schema, "model", JsonSchemas.stringProperty("Optional model metadata for future per-subagent model routing."));
        JsonSchemas.addOptional(schema, "mcp_servers", JsonSchemas.stringProperty("Optional comma-separated MCP server metadata."));
        JsonSchemas.addOptional(schema, "disallowed_tools", JsonSchemas.stringProperty("Optional comma-separated read-only tools to subtract from tools."));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        String name = arguments.path("name").asText("").trim();
        String description = arguments.path("description").asText("").trim();
        String prompt = arguments.path("prompt").asText("").trim();
        if (!name.matches("[A-Za-z0-9_-]+")) {
            return ToolResult.failure("name must match [A-Za-z0-9_-]+");
        }
        if (description.isEmpty()) {
            return ToolResult.failure("description is required.");
        }
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            return ToolResult.failure("description is too long: " + description.length() + " > " + MAX_DESCRIPTION_CHARS);
        }
        if (prompt.isEmpty()) {
            return ToolResult.failure("prompt is required.");
        }
        if (prompt.length() > MAX_PROMPT_CHARS) {
            return ToolResult.failure("prompt is too long: " + prompt.length() + " > " + MAX_PROMPT_CHARS);
        }

        List<String> tools;
        try {
            tools = parseTools(arguments.path("tools"));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
        int maxSteps = parseMaxSteps(arguments.path("max_steps").asText(""));
        boolean memory = parseBool(arguments.path("memory").asText("true"));
        String permissionMode = arguments.path("permission_mode").asText("strict").trim();
        String model = singleLine(arguments.path("model").asText("").trim());
        String mcpServers = safeNameList(arguments.path("mcp_servers").asText(""));
        String disallowedTools;
        try {
            disallowedTools = joinTools(parseSafeToolText(arguments.path("disallowed_tools").asText("")));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
        File directory = new File(workspace, ".harness/agents").getCanonicalFile();
        File file = new File(directory, name + ".md").getCanonicalFile();
        if (!isInside(directory, file)) {
            return ToolResult.failure("resolved subagent path escapes .harness/agents");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            return ToolResult.failure("Cannot create directory: " + relative(directory));
        }

        String markdown = render(name, description, tools, maxSteps, memory, permissionMode, model, mcpServers, disallowedTools, prompt);
        Files.write(file.toPath(), markdown.getBytes(StandardCharsets.UTF_8));
        log.block("SUBAGENT", "Wrote project subagent", markdown);
        return ToolResult.success("Saved project subagent: " + relative(file)
                + "\nRestart the harness to load it into SubagentRegistry.");
    }

    private List<String> parseTools(JsonNode node) {
        List<String> tools = new ArrayList<String>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String tool = item.asText("").trim();
                if (!SAFE_TOOLS.contains(tool)) {
                    throw new IllegalArgumentException("tool is not allowed for project subagents: " + tool);
                }
                if (!tools.contains(tool)) {
                    tools.add(tool);
                }
            }
        }
        if (tools.isEmpty()) {
            tools.addAll(SAFE_TOOLS);
        }
        return tools;
    }

    private int parseMaxSteps(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 8;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(1, Math.min(parsed, MAX_STEPS));
        } catch (Exception ignored) {
            return 8;
        }
    }

    private boolean parseBool(String value) {
        return !"false".equalsIgnoreCase(value == null ? "" : value.trim());
    }

    private String render(String name,
                          String description,
                          List<String> tools,
                          int maxSteps,
                          boolean memory,
                          String permissionMode,
                          String model,
                          String mcpServers,
                          String disallowedTools,
                          String prompt) {
        StringBuilder out = new StringBuilder();
        out.append("---\n");
        out.append("name: ").append(name).append('\n');
        out.append("description: ").append(singleLine(description)).append('\n');
        out.append("tools: ").append(joinTools(tools)).append('\n');
        out.append("max_steps: ").append(maxSteps).append('\n');
        out.append("memory: ").append(memory).append('\n');
        out.append("permission_mode: ").append(singleLine(permissionMode)).append('\n');
        if (!model.isEmpty()) {
            out.append("model: ").append(model).append('\n');
        }
        if (!mcpServers.isEmpty()) {
            out.append("mcp_servers: ").append(mcpServers).append('\n');
        }
        if (!disallowedTools.isEmpty()) {
            out.append("disallowed_tools: ").append(disallowedTools).append('\n');
        }
        out.append("---\n\n");
        out.append(prompt).append('\n');
        return out.toString();
    }

    private String joinTools(List<String> tools) {
        StringBuilder out = new StringBuilder();
        for (String tool : tools) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(tool);
        }
        return out.toString();
    }

    private String singleLine(String value) {
        return value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
    }

    private List<String> parseSafeToolText(String value) {
        List<String> tools = new ArrayList<String>();
        if (value == null || value.trim().isEmpty()) {
            return tools;
        }
        String normalized = value.replace("[", "").replace("]", "");
        String[] parts = normalized.split(",");
        for (String part : parts) {
            String tool = part.trim();
            if (!SAFE_TOOLS.contains(tool)) {
                throw new IllegalArgumentException("tool is not allowed for project subagents: " + tool);
            }
            if (!tools.contains(tool)) {
                tools.add(tool);
            }
        }
        return tools;
    }

    private String safeNameList(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String normalized = value.replace("[", "").replace("]", "");
        String[] parts = normalized.split(",");
        for (String part : parts) {
            String item = part.trim();
            if (!item.matches("[A-Za-z0-9_-]+")) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(item);
        }
        return out.toString();
    }

    private boolean isInside(File directory, File file) {
        String root = directory.getAbsolutePath();
        String target = file.getAbsolutePath();
        return target.equals(root) || target.startsWith(root + File.separator);
    }

    private String relative(File file) {
        String root = workspace.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.startsWith(root + File.separator)) {
            return path.substring(root.length() + 1).replace(File.separatorChar, '/');
        }
        return path;
    }
}
