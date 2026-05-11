package com.ahi.harness.subagent;

import com.ahi.harness.ConsoleLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SubagentRegistry {
    private final Map<String, SubagentDefinition> definitions = new LinkedHashMap<String, SubagentDefinition>();

    public SubagentRegistry() {
        registerBuiltIns();
    }

    public SubagentRegistry(File workspace, ConsoleLog log) {
        registerBuiltIns();
        for (SubagentDefinition definition : new SubagentLoader(log).load(workspace)) {
            register(definition);
            log.info("SUBAGENT", "loaded project subagent: " + definition.name()
                    + " tools=" + definition.allowedTools()
                    + " max_steps=" + definition.defaultMaxSteps());
        }
    }

    private void registerBuiltIns() {
        register(new SubagentDefinition(
                "explorer",
                "Read-only codebase explorer for focused investigation tasks.",
                "You are the explorer subagent. Investigate the local codebase with read-only tools. "
                        + "Prefer list_files, grep, and read_file. Do not propose edits unless asked; return concise findings with file references.",
                Arrays.asList("list_files", "read_file", "grep"),
                8
        ));
        register(new SubagentDefinition(
                "reviewer",
                "Read-only reviewer for bugs, risks, regressions, and missing tests.",
                "You are the reviewer subagent. Review the requested code paths for concrete bugs, regressions, and test gaps. "
                        + "Use read-only tools only. Lead with findings and include file references when possible.",
                Arrays.asList("list_files", "read_file", "grep"),
                8
        ));
    }

    public void register(SubagentDefinition definition) {
        definitions.put(definition.name(), definition);
    }

    public SubagentDefinition get(String name) {
        return definitions.get(name);
    }

    public List<SubagentDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<SubagentDefinition>(definitions.values()));
    }
}
