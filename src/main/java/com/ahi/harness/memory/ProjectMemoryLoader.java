package com.ahi.harness.memory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ProjectMemoryLoader {
    private static final int MAX_CHARS_PER_FILE = 12000;

    public String load(File workspace) throws Exception {
        StringBuilder memory = new StringBuilder();
        appendIfExists(memory, workspace, "HARNESS.md");
        appendIfExists(memory, workspace, "CLAUDE.md");
        appendIfExists(memory, workspace, "AGENTS.md");
        return memory.toString();
    }

    private void appendIfExists(StringBuilder memory, File workspace, String name) throws Exception {
        File file = new File(workspace, name);
        if (!file.exists() || !file.isFile()) {
            return;
        }
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (content.length() > MAX_CHARS_PER_FILE) {
            content = content.substring(0, MAX_CHARS_PER_FILE) + "\n...<truncated>";
        }
        if (memory.length() > 0) {
            memory.append("\n\n");
        }
        memory.append("Project memory from ").append(name).append(":\n").append(content);
    }
}
