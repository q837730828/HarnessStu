package com.ahi.harness.subagent;

import java.util.Collections;
import java.util.List;

public class SubagentDefinition {
    private final String name;
    private final String description;
    private final String systemPrompt;
    private final List<String> allowedTools;
    private final List<String> disallowedTools;
    private final List<String> mcpServers;
    private final String model;
    private final String permissionMode;
    private final boolean memory;
    private final int defaultMaxSteps;

    public SubagentDefinition(String name,
                              String description,
                              String systemPrompt,
                              List<String> allowedTools,
                              int defaultMaxSteps) {
        this(name, description, systemPrompt, allowedTools, Collections.<String>emptyList(), Collections.<String>emptyList(), "", "strict", true, defaultMaxSteps);
    }

    public SubagentDefinition(String name,
                              String description,
                              String systemPrompt,
                              List<String> allowedTools,
                              List<String> disallowedTools,
                              List<String> mcpServers,
                              String model,
                              String permissionMode,
                              boolean memory,
                              int defaultMaxSteps) {
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.allowedTools = allowedTools;
        this.disallowedTools = disallowedTools;
        this.mcpServers = mcpServers;
        this.model = model == null ? "" : model;
        this.permissionMode = permissionMode == null || permissionMode.trim().isEmpty() ? "strict" : permissionMode.trim();
        this.memory = memory;
        this.defaultMaxSteps = defaultMaxSteps;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public List<String> allowedTools() {
        return Collections.unmodifiableList(allowedTools);
    }

    public List<String> disallowedTools() {
        return Collections.unmodifiableList(disallowedTools);
    }

    public List<String> mcpServers() {
        return Collections.unmodifiableList(mcpServers);
    }

    public String model() {
        return model;
    }

    public String permissionMode() {
        return permissionMode;
    }

    public boolean memory() {
        return memory;
    }

    public int defaultMaxSteps() {
        return defaultMaxSteps;
    }
}
