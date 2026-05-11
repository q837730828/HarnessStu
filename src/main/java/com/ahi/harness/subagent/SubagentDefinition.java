package com.ahi.harness.subagent;

import java.util.Collections;
import java.util.List;

public class SubagentDefinition {
    private final String name;
    private final String description;
    private final String systemPrompt;
    private final List<String> allowedTools;
    private final int defaultMaxSteps;

    public SubagentDefinition(String name,
                              String description,
                              String systemPrompt,
                              List<String> allowedTools,
                              int defaultMaxSteps) {
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.allowedTools = allowedTools;
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

    public int defaultMaxSteps() {
        return defaultMaxSteps;
    }
}
