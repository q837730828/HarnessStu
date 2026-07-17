package com.ahi.harness.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stable description of the Agent capability provider.
 *
 * This is the local equivalent of an Agent Card: it says who the runtime is,
 * which model it uses, and which tools/capabilities it may expose. It does not
 * contain mutable execution state; that belongs to AgentRun.
 */
public class AgentIdentity {
    private final String id;
    private final String name;
    private final String version;
    private final String model;
    private final List<String> capabilities;
    private final List<String> tools;
    private final String createdAt;

    public AgentIdentity(String id,
                         String name,
                         String version,
                         String model,
                         List<String> capabilities,
                         List<String> tools,
                         String createdAt) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.model = model;
        this.capabilities = copy(capabilities);
        this.tools = copy(tools);
        this.createdAt = createdAt;
    }

    public static AgentIdentity create(String name, String model, List<String> tools) {
        List<String> capabilities = new ArrayList<String>();
        capabilities.add("tool_calling");
        capabilities.add("checkpointing");
        capabilities.add("interrupts");
        capabilities.add("artifacts");
        return new AgentIdentity(
                ProtocolIds.next("agent"),
                name,
                "0.1.0",
                model,
                capabilities,
                tools,
                ProtocolIds.now()
        );
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getModel() {
        return model;
    }

    public List<String> getCapabilities() {
        return Collections.unmodifiableList(capabilities);
    }

    public List<String> getTools() {
        return Collections.unmodifiableList(tools);
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public int getSchemaVersion() {
        return 1;
    }

    private List<String> copy(List<String> values) {
        return values == null ? new ArrayList<String>() : new ArrayList<String>(values);
    }
}
