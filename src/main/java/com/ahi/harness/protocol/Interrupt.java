package com.ahi.harness.protocol;

/**
 * Explicit Human-in-the-Loop request.
 *
 * The current CLI resolves interrupts synchronously, but persisting OPEN and
 * RESOLVED/REJECTED states gives the same operation a protocol representation
 * that can later be exposed over HTTP or resumed by another process.
 */
public class Interrupt {
    public enum Type {
        TOOL_APPROVAL,
        INPUT_REQUIRED,
        AUTH_REQUIRED
    }

    public enum Status {
        OPEN,
        RESOLVED,
        REJECTED
    }

    private final String id;
    private final String threadId;
    private final String runId;
    private final String stepId;
    private final Type type;
    private final String prompt;
    private final String payload;
    private final String createdAt;
    private Status status;
    private String resolution;
    private String resolvedAt;

    public Interrupt(String threadId,
                     String runId,
                     String stepId,
                     Type type,
                     String prompt,
                     String payload) {
        this.id = ProtocolIds.next("interrupt");
        this.threadId = threadId;
        this.runId = runId;
        this.stepId = stepId;
        this.type = type;
        this.prompt = prompt;
        this.payload = payload;
        this.createdAt = ProtocolIds.now();
        this.status = Status.OPEN;
    }

    public void resolve(String value) {
        requireOpen();
        status = Status.RESOLVED;
        resolution = value;
        resolvedAt = ProtocolIds.now();
    }

    public void reject(String reason) {
        requireOpen();
        status = Status.REJECTED;
        resolution = reason;
        resolvedAt = ProtocolIds.now();
    }

    private void requireOpen() {
        if (status != Status.OPEN) {
            throw new IllegalStateException("Interrupt " + id + " is already " + status);
        }
    }

    public String getId() { return id; }
    public String getThreadId() { return threadId; }
    public String getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public Type getType() { return type; }
    public String getPrompt() { return prompt; }
    public String getPayload() { return payload; }
    public String getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public String getResolution() { return resolution; }
    public String getResolvedAt() { return resolvedAt; }
    public int getSchemaVersion() { return 1; }
}
