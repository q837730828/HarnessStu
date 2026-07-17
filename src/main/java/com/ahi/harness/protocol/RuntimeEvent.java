package com.ahi.harness.protocol;

/**
 * Ordered fact emitted while a Run changes.
 *
 * Events are deliberately broader than token streaming: Run status, Step
 * progress, checkpoints, interrupts, artifacts and errors all share one stream.
 */
public class RuntimeEvent {
    public enum Channel {
        STATE,
        STEP,
        MESSAGE,
        TOOL,
        ARTIFACT,
        ERROR
    }

    public enum Type {
        RUN_CREATED,
        RUN_STARTED,
        RUN_INPUT_REQUIRED,
        RUN_AUTH_REQUIRED,
        RUN_RESUMED,
        RUN_COMPLETED,
        RUN_FAILED,
        RUN_CANCELLED,
        RUN_TIMED_OUT,
        MESSAGE_CREATED,
        TEXT_DELTA,
        TOOL_CALL_REQUESTED,
        TOOL_RESULT_APPENDED,
        STEP_STARTED,
        STEP_COMPLETED,
        STEP_FAILED,
        STEP_BLOCKED,
        PERMISSION_DECIDED,
        CHECKPOINT_CREATED,
        INTERRUPT_CREATED,
        INTERRUPT_RESOLVED,
        ARTIFACT_CREATED
    }

    private final String id;
    private final String threadId;
    private final String runId;
    private final String stepId;
    // Correlation is stored directly instead of requiring consumers to infer
    // it by joining Run and Step snapshots.
    private final String traceId;
    private final String spanId;
    private final long sequence;
    private final Type type;
    private final Channel channel;
    private final String timestamp;
    private final String detail;

    public RuntimeEvent(String threadId,
                        String runId,
                        String stepId,
                        long sequence,
                        Type type,
                        String detail) {
        this(threadId, runId, stepId, null, null, sequence, type, detail);
    }

    public RuntimeEvent(String threadId,
                        String runId,
                        String stepId,
                        String traceId,
                        String spanId,
                        long sequence,
                        Type type,
                        String detail) {
        this(ProtocolIds.next("event"), threadId, runId, stepId, traceId, spanId, sequence,
                type, channelFor(type), ProtocolIds.now(), detail);
    }

    private RuntimeEvent(String id,
                         String threadId,
                         String runId,
                         String stepId,
                         String traceId,
                         String spanId,
                         long sequence,
                         Type type,
                         Channel channel,
                         String timestamp,
                         String detail) {
        this.id = id;
        this.threadId = threadId;
        this.runId = runId;
        this.stepId = stepId;
        this.traceId = traceId;
        this.spanId = spanId;
        this.sequence = sequence;
        this.type = type;
        this.channel = channel;
        this.timestamp = timestamp;
        this.detail = detail;
    }

    public static RuntimeEvent rehydrate(String id,
                                         String threadId,
                                         String runId,
                                         String stepId,
                                         String traceId,
                                         String spanId,
                                         long sequence,
                                         Type type,
                                         Channel channel,
                                         String timestamp,
                                         String detail) {
        return new RuntimeEvent(id, threadId, runId, stepId, traceId, spanId, sequence, type,
                channel == null ? channelFor(type) : channel, timestamp, detail);
    }

    private static Channel channelFor(Type type) {
        if (type == Type.ARTIFACT_CREATED) {
            return Channel.ARTIFACT;
        }
        if (type == Type.PERMISSION_DECIDED || type == Type.TOOL_CALL_REQUESTED
                || type == Type.TOOL_RESULT_APPENDED) {
            return Channel.TOOL;
        }
        if (type == Type.MESSAGE_CREATED || type == Type.TEXT_DELTA) {
            return Channel.MESSAGE;
        }
        if (type == Type.RUN_FAILED || type == Type.RUN_TIMED_OUT
                || type == Type.STEP_FAILED || type == Type.STEP_BLOCKED) {
            return Channel.ERROR;
        }
        if (type == Type.STEP_STARTED || type == Type.STEP_COMPLETED) {
            return Channel.STEP;
        }
        return Channel.STATE;
    }

    public String getId() { return id; }
    public String getThreadId() { return threadId; }
    public String getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public long getSequence() { return sequence; }
    public Type getType() { return type; }
    public Channel getChannel() { return channel; }
    public String getTimestamp() { return timestamp; }
    public String getDetail() { return detail; }
    public int getSchemaVersion() { return 1; }
}
