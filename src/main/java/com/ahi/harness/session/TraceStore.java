package com.ahi.harness.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Best-effort structured runtime trace.
 *
 * Trace writes are intentionally non-fatal; losing observability must not stop a
 * user task from running.
 */
public class TraceStore {
    private final ObjectMapper mapper = new ObjectMapper();
    private final File file;

    public TraceStore(File workspace) throws Exception {
        File directory = new File(workspace, ".harness/traces");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create trace directory: " + directory.getAbsolutePath());
        }
        String name = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                .replace(':', '-')
                .replace('.', '-')
                + ".jsonl";
        this.file = new File(directory, name);
    }

    public synchronized void record(String event, String detail) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("ts", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            node.put("event", event);
            node.put("detail", detail == null ? "" : detail);
            Writer writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
            try {
                writer.write(mapper.writeValueAsString(node));
                writer.write("\n");
            } finally {
                writer.close();
            }
        } catch (Exception ignored) {
            // Tracing must never break the agent loop.
        }
    }

    public String path() {
        return file.getAbsolutePath();
    }
}
