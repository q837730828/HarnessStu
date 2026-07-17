package com.ahi.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Loads task-specific local instructions only when routed to a matching skill.
 */
public class SkillLoadTool implements Tool {
    private static final int MAX_CHARS = 24000;

    private final File workspace;

    public SkillLoadTool(File workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "skill_load";
    }

    @Override
    public String description() {
        return "Load a task-specific Markdown skill on demand from skills/<name>.md, skills/<name>/SKILL.md, or .harness/skills/<name>.md.";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = JsonSchemas.object();
        JsonSchemas.addRequired(schema, "name", JsonSchemas.stringProperty("Skill name using letters, numbers, dash, underscore, or slash."));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        String name = arguments.path("name").asText("").trim().replace('\\', '/');
        if (!safeName(name)) {
            return ToolResult.failure("Invalid skill name: use letters, numbers, dash, underscore, dot, or slash without '..'.");
        }
        File file = resolveSkill(name);
        if (file == null || !file.exists() || !file.isFile()) {
            return ToolResult.failure("Skill not found: " + name);
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return ToolResult.success(trim(text, MAX_CHARS));
    }

    private File resolveSkill(String name) throws Exception {
        // Support both a flat skills/foo.md style and a directory-based
        // skills/foo/SKILL.md style.
        String[] candidates = new String[] {
                "skills/" + name + ".md",
                "skills/" + name + "/SKILL.md",
                ".harness/skills/" + name + ".md",
                ".harness/skills/" + name + "/SKILL.md"
        };
        for (String candidate : candidates) {
            File file = WorkspacePaths.resolveInside(workspace, candidate);
            if (file.exists() && file.isFile()) {
                return file;
            }
        }
        return null;
    }

    private boolean safeName(String name) {
        // Skill names are path fragments. Keep the allowed character set small
        // and block traversal before resolving inside the workspace.
        if (name.isEmpty() || name.contains("..") || name.startsWith("/") || name.startsWith(".")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_' || ch == '/' || ch == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private String trim(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n...<truncated " + (value.length() - maxChars) + " chars>";
    }
}
