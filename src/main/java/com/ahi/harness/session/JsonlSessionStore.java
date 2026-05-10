package com.ahi.harness.session;

import com.ahi.harness.ConsoleLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

public class JsonlSessionStore implements SessionStore {
    private final ObjectMapper mapper = new ObjectMapper();
    private final File file;
    private final ConsoleLog log;

    public JsonlSessionStore(File directory, ConsoleLog log) {
        this.log = log;
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create session directory: " + directory.getAbsolutePath());
        }
        String name = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                .replace(':', '-')
                .replace('.', '-')
                + ".jsonl";
        this.file = new File(directory, name);
        this.log.info("HARNESS", "session_log = " + file.getAbsolutePath());
    }

    @Override
    public synchronized void append(String type, String content) throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("ts", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        node.put("type", type);
        node.put("content", content == null ? "" : content);
        Writer writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
        try {
            writer.write(mapper.writeValueAsString(node));
            writer.write("\n");
        } finally {
            writer.close();
        }
    }
}
