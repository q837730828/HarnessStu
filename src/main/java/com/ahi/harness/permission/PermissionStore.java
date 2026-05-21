package com.ahi.harness.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

public class PermissionStore {
    private final File file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> allowRules = new LinkedHashSet<String>();

    public PermissionStore(File workspace) {
        this.file = new File(workspace, ".harness/permissions.json");
        load();
    }

    public boolean allows(String rule) {
        return allowRules.contains(rule);
    }

    public void allow(String rule) throws Exception {
        if (allowRules.add(rule)) {
            save();
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JsonNode rules = root.path("allow_rules");
            if (rules.isArray()) {
                for (JsonNode rule : rules) {
                    if (rule.isTextual()) {
                        allowRules.add(rule.asText());
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void save() throws Exception {
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create permission directory: " + parent.getAbsolutePath());
        }
        ObjectNode root = mapper.createObjectNode();
        ArrayNode rules = mapper.createArrayNode();
        for (String rule : allowRules) {
            rules.add(rule);
        }
        root.set("allow_rules", rules);
        Files.write(file.toPath(), (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
