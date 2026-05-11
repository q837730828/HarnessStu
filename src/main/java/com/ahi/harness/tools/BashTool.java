package com.ahi.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.ahi.harness.ConsoleLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class BashTool implements Tool {
    private static final int MAX_CHARS = 12000;
    private final File workspace;
    private final ConsoleLog log;
    private final int defaultTimeoutSeconds;
    private final int maxTimeoutSeconds;

    public BashTool(File workspace, ConsoleLog log, int defaultTimeoutSeconds, int maxTimeoutSeconds) {
        this.workspace = workspace;
        this.log = log;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.maxTimeoutSeconds = maxTimeoutSeconds;
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
        JsonSchemas.addOptional(schema, "timeout_seconds", JsonSchemas.stringProperty("Timeout in seconds. Defaults to 60, maximum 300."));
        JsonSchemas.addOptional(schema, "purpose", JsonSchemas.stringProperty("Short reason for running this validation command."));
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) throws Exception {
        String command = arguments.path("command").asText();
        if (command.trim().isEmpty()) {
            return ToolResult.failure("command is required");
        }
        int timeoutSeconds = parseTimeout(arguments.path("timeout_seconds").asText(String.valueOf(defaultTimeoutSeconds)));
        String purpose = arguments.path("purpose").asText("");

        List<String> fullCommand = new ArrayList<String>();
        fullCommand.add("powershell");
        fullCommand.add("-NoProfile");
        fullCommand.add("-Command");
        fullCommand.add("chcp 65001 > $null; "
                + "[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false); "
                + "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); "
                + "$OutputEncoding=[System.Text.UTF8Encoding]::new($false); "
                + command);

        long started = System.currentTimeMillis();
        log.info("VALIDATION", "Run command: " + command
                + (purpose.trim().isEmpty() ? "" : " | purpose: " + purpose)
                + " | timeout=" + timeoutSeconds + "s");

        ProcessBuilder builder = new ProcessBuilder(fullCommand);
        builder.directory(workspace);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> outputFuture = executor.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                StringBuilder out = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.forName("UTF-8")));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() < MAX_CHARS) {
                        out.append(line).append('\n');
                    }
                }
                return out.toString();
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return ToolResult.failure("Command interrupted: " + command);
        }

        if (!finished) {
            process.destroyForcibly();
            executor.shutdownNow();
            long elapsed = System.currentTimeMillis() - started;
            return ToolResult.failure("command=" + command + "\n"
                    + "exit_code=timeout\n"
                    + "elapsed_ms=" + elapsed + "\n"
                    + "Timed out after " + timeoutSeconds + "s.");
        }

        String out = outputFuture.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        int exit = process.exitValue();
        long elapsed = System.currentTimeMillis() - started;
        String text = "command=" + command + "\n"
                + "exit_code=" + exit + "\n"
                + "elapsed_ms=" + elapsed + "\n"
                + "output:\n"
                + out;
        if (out.length() >= MAX_CHARS) {
            text += "...truncated at " + MAX_CHARS + " chars\n";
        }
        log.info("VALIDATION", "Command finished: exit_code=" + exit + ", elapsed_ms=" + elapsed);
        return exit == 0 ? ToolResult.success(text) : ToolResult.failure(text);
    }

    private int parseTimeout(String text) {
        try {
            int value = Integer.parseInt(text);
            return Math.max(1, Math.min(value, maxTimeoutSeconds));
        } catch (Exception ignored) {
            return defaultTimeoutSeconds;
        }
    }
}
