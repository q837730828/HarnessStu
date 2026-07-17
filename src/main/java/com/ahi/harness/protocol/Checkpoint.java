package com.ahi.harness.protocol;

/**
 * Recoverable snapshot taken at a stable point in a Run.
 *
 * The object stores metadata; RuntimeStore stores the complete Message snapshot
 * beside it. "resumable" describes message-level recovery only and must not be
 * confused with automatic rollback of external side effects.
 */
public class Checkpoint {
    private final String id;
    private final String threadId;
    private final String runId;
    private final String stepId;
    private final String traceId;
    private final String spanId;
    private final String parentCheckpointId;
    private final int sequence;
    private final String reason;
    private final int messageCount;
    private final int estimatedTokens;
    private final boolean resumable;
    private final String createdAt;

    public Checkpoint(String threadId,
                      String runId,
                      String stepId,
                      int sequence,
                      String reason,
                      int messageCount,
                      int estimatedTokens,
                      boolean resumable) {
        this(threadId, runId, stepId, null, null, null, sequence, reason,
                messageCount, estimatedTokens, resumable);
    }

    public Checkpoint(String threadId,
                      String runId,
                      String stepId,
                      String traceId,
                      String spanId,
                      String parentCheckpointId,
                      int sequence,
                      String reason,
                      int messageCount,
                      int estimatedTokens,
                      boolean resumable) {
        this.id = ProtocolIds.next("checkpoint");
        this.threadId = threadId;
        this.runId = runId;
        this.stepId = stepId;
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentCheckpointId = parentCheckpointId;
        this.sequence = sequence;
        this.reason = reason;
        this.messageCount = messageCount;
        this.estimatedTokens = estimatedTokens;
        this.resumable = resumable;
        this.createdAt = ProtocolIds.now();
    }

    public String getId() { return id; }
    public String getThreadId() { return threadId; }
    public String getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentCheckpointId() { return parentCheckpointId; }
    public int getSequence() { return sequence; }
    public String getReason() { return reason; }
    public int getMessageCount() { return messageCount; }
    public int getEstimatedTokens() { return estimatedTokens; }
    public boolean isResumable() { return resumable; }
    public String getCreatedAt() { return createdAt; }
    public int getSchemaVersion() { return 1; }
}
