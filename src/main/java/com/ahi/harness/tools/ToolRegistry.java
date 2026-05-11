package com.ahi.harness.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public int unregisterPrefix(String prefix) {
        List<String> names = new ArrayList<String>(tools.keySet());
        int removed = 0;
        for (String name : names) {
            if (name.startsWith(prefix)) {
                tools.remove(name);
                removed++;
            }
        }
        return removed;
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> all() {
        return Collections.unmodifiableList(new ArrayList<Tool>(tools.values()));
    }
}
