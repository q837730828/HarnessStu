package com.ahi.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class BashTool implements Tool {
    private static final int MAX_CHARS = 12000;
    private final File workspace;

    public BashTool(File workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Run a safe, allowlisted command in the workspace. Use for git status, tests, and build checks.";
    }

    @Override
    public ObjectNode parameters() {
        ObjectNode schema = JsonSchemas.object();
        JsonSchemas.addRequired(schema, "command", JsonSchemas.stringProperty("Command to run in PowerShell."));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        String command = arguments.path("command").asText();
        if (command.trim().isEmpty()) {
            return ToolResult.failure("command is required");
        }

        List<String> fullCommand = new ArrayList<String>();
        fullCommand.add("powershell");
        fullCommand.add("-NoProfile");
        fullCommand.add("-Command");
        fullCommand.add("chcp 65001 > $null; "
                + "[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false); "
                + "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); "
                + "$OutputEncoding=[System.Text.UTF8Encoding]::new($false); "
                + command);

        ProcessBuilder builder = new ProcessBuilder(fullCommand);
        builder.directory(workspace);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder out = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.forName("UTF-8")));
        String line;
        while ((line = reader.readLine()) != null) {
            if (out.length() < MAX_CHARS) {
                out.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return ToolResult.failure("Command timed out after 60s.\n" + out.toString());
        }
        int exit = process.exitValue();
        String text = "exit_code=" + exit + "\n" + out.toString();
        if (out.length() >= MAX_CHARS) {
            text += "...truncated at " + MAX_CHARS + " chars\n";
        }
        return exit == 0 ? ToolResult.success(text) : ToolResult.failure(text);
    }
}
