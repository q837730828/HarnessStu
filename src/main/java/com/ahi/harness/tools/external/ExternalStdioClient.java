package com.ahi.harness.tools.external;

import com.ahi.harness.config.HarnessSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ExternalStdioClient {
    private final File workspace;
    private final HarnessSettings.ExternalToolServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExternalStdioClient(File workspace, HarnessSettings.ExternalToolServer server) {
        this.workspace = workspace;
        this.server = server;
    }

    public JsonNode request(JsonNode payload, int timeoutSeconds) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(server.command());
        command.addAll(server.args());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        writer.write(mapper.writeValueAsString(payload));
        writer.write("\n");
        writer.flush();
        writer.close();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> lineFuture = executor.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                return reader.readLine();
            }
        });

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            executor.shutdownNow();
            throw new IllegalStateException("External tool server timed out: " + server.name());
        }
        String line = lineFuture.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();
        int exit = process.exitValue();
        if (line == null || line.trim().isEmpty()) {
            throw new IllegalStateException("External tool server returned no JSON. exit_code=" + exit);
        }
        JsonNode response = mapper.readTree(line);
        if (exit != 0) {
            throw new IllegalStateException("External tool server failed. exit_code=" + exit + ", response=" + line);
        }
        return response;
    }
}
