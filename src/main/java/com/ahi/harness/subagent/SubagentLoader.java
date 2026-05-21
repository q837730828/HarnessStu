package com.ahi.harness.subagent;

import com.ahi.harness.ConsoleLog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SubagentLoader {
    private static final int MAX_STEPS = 16;
    private static final List<String> SAFE_TOOLS = Arrays.asList("list_files", "read_file", "grep");

    private final ConsoleLog log;

    public SubagentLoader(ConsoleLog log) {
        this.log = log;
    }

    public List<SubagentDefinition> load(File workspace) {
        List<SubagentDefinition> definitions = new ArrayList<SubagentDefinition>();
        File directory = new File(workspace, ".harness/agents");
        if (!directory.exists() || !directory.isDirectory()) {
            return definitions;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return definitions;
        }
        Arrays.sort(files);
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".md")) {
                continue;
            }
            try {
                definitions.add(parse(file));
            } catch (Exception e) {
                log.error("SUBAGENT", "Skipping " + file.getName() + ": " + e.getMessage());
            }
        }
        return definitions;
    }

    private SubagentDefinition parse(File file) throws Exception {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        if (!text.startsWith("---\n")) {
            throw new IllegalArgumentException("missing YAML-style frontmatter");
        }
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalArgumentException("frontmatter must end with ---");
        }

        Map<String, String> fields = parseFields(text.substring(4, end));
        String body = text.substring(end + "\n---\n".length()).trim();
        String name = required(fields, "name");
        String description = required(fields, "description");
        List<String> tools = parseTools(fields.containsKey("tools") ? fields.get("tools") : "list_files, read_file, grep");
        List<String> disallowedTools = parseToolList(fields.get("disallowed_tools"));
        List<String> mcpServers = parseList(fields.get("mcp_servers"));
        String model = field(fields, "model", "");
        String permissionMode = field(fields, "permission_mode", "strict");
        boolean memory = bool(fields.get("memory"), true);
        int maxSteps = parseMaxSteps(fields.get("max_steps"));

        if (!name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("name must match [A-Za-z0-9_-]+");
        }
        if (body.isEmpty()) {
            throw new IllegalArgumentException("body system prompt is required");
        }

        return new SubagentDefinition(name, description, body, tools, disallowedTools, mcpServers, model, permissionMode, memory, maxSteps);
    }

    private Map<String, String> parseFields(String frontmatter) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        String[] lines = frontmatter.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            fields.put(key, stripQuotes(value));
        }
        return fields;
    }

    private String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private List<String> parseTools(String value) {
        List<String> tools = new ArrayList<String>();
        String normalized = value.replace("[", "").replace("]", "");
        String[] parts = normalized.split(",");
        for (String part : parts) {
            String tool = stripQuotes(part.trim());
            if (tool.isEmpty()) {
                continue;
            }
            if (!SAFE_TOOLS.contains(tool)) {
                throw new IllegalArgumentException("tool is not allowed for project subagents: " + tool);
            }
            if (!tools.contains(tool)) {
                tools.add(tool);
            }
        }
        if (tools.isEmpty()) {
            throw new IllegalArgumentException("tools must contain at least one safe read-only tool");
        }
        return tools;
    }

    private List<String> parseToolList(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<String>();
        }
        List<String> tools = parseTools(value);
        return tools;
    }

    private List<String> parseList(String value) {
        List<String> values = new ArrayList<String>();
        if (value == null || value.trim().isEmpty()) {
            return values;
        }
        String normalized = value.replace("[", "").replace("]", "");
        String[] parts = normalized.split(",");
        for (String part : parts) {
            String item = stripQuotes(part.trim());
            if (!item.isEmpty() && item.matches("[A-Za-z0-9_-]+")) {
                values.add(item);
            }
        }
        return values;
    }

    private String field(Map<String, String> fields, String name, String fallback) {
        String value = fields.get(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private boolean bool(String value, boolean fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    private int parseMaxSteps(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 8;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(1, Math.min(parsed, MAX_STEPS));
        } catch (Exception e) {
            throw new IllegalArgumentException("max_steps must be an integer");
        }
    }

    private String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
