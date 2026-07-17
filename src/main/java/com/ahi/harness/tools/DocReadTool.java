package com.ahi.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * On-demand documentation reader.
 *
 * This keeps long docs out of the always-on system prompt while still making
 * them discoverable to the agent.
 */
public class DocReadTool implements Tool {
    private static final int MAX_CHARS = 24000;
    private static final Set<String> ROOT_DOCS = new HashSet<String>(Arrays.asList(
            "README.md", "HARNESS.md", "AGENTS.md", "CLAUDE.md"
    ));

    private final File workspace;

    public DocReadTool(File workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "doc_read";
    }

    @Override
    public String description() {
        return "Read a project documentation file on demand. Use for docs/*.md, README.md, HARNESS.md, AGENTS.md, or CLAUDE.md.";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = JsonSchemas.object();
        JsonSchemas.addRequired(schema, "path", JsonSchemas.stringProperty("Documentation path, for example docs/ARCHITECTURE.md or AGENTS.md."));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        String path = arguments.path("path").asText("").trim().replace('\\', '/');
        if (!allowed(path)) {
            return ToolResult.failure("doc_read only allows docs/*.md or root README.md, HARNESS.md, AGENTS.md, CLAUDE.md.");
        }
        File file = WorkspacePaths.resolveInside(workspace, path);
        if (!file.exists() || !file.isFile()) {
            return ToolResult.failure("Documentation file does not exist: " + path);
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return ToolResult.success(trim(text, MAX_CHARS));
    }

    private boolean allowed(String path) {
        // Limit this tool to intentional project documentation. General file
        // reads still go through read_file and its runtime-file restrictions.
        if (ROOT_DOCS.contains(path)) {
            return true;
        }
        return path.startsWith("docs/") && path.endsWith(".md") && !path.contains("/../");
    }

    private String trim(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n...<truncated " + (value.length() - maxChars) + " chars>";
    }
}
