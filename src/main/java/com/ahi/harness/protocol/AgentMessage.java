package com.ahi.harness.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stable protocol message associated with a Thread and optional Run/Step. */
public class AgentMessage {
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    private final String id;
    private final String threadId;
    private final String runId;
    private final String stepId;
    private final Role role;
    private final List<MessagePart> parts;
    private final String createdAt;

    public AgentMessage(String threadId,
                        String runId,
                        String stepId,
                        Role role,
                        List<MessagePart> parts) {
        this.id = ProtocolIds.next("message");
        this.threadId = threadId;
        this.runId = runId;
        this.stepId = stepId;
        this.role = role;
        this.parts = parts == null ? new ArrayList<MessagePart>() : new ArrayList<MessagePart>(parts);
        this.createdAt = ProtocolIds.now();
    }

    public String getId() { return id; }
    public String getThreadId() { return threadId; }
    public String getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public Role getRole() { return role; }
    public List<MessagePart> getParts() { return Collections.unmodifiableList(parts); }
    public String getCreatedAt() { return createdAt; }
    public int getSchemaVersion() { return 1; }
}
