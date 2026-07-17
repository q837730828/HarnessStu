package com.ahi.harness.protocol;

/**
 * Observable unit of work inside a Run.
 *
 * A Step does not dictate how the loop is implemented. It records meaningful
 * actions (model, guardrail, tool, compaction, subagent) in a framework-neutral
 * form that an external observer can understand.
 */
public class RunStep {
    public enum Type {
        MODEL_CALL,
        GUARDRAIL,
        TOOL_CALL,
        COMPACTION,
        SUBAGENT_TASK
    }

    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        BLOCKED
    }

    private final String id;
    private final String runId;
    private final int sequence;
    private final Type type;
    private final String name;
    private final String inputSummary;
    private final String toolCallId;
    private final String startedAt;
    private String completedAt;
    private Status status;
    private String outputSummary;
    private String error;

    private RunStep(String id,
                    String runId,
                    int sequence,
                    Type type,
                    String name,
                    String inputSummary,
                    String toolCallId,
                    String startedAt) {
        this.id = id;
        this.runId = runId;
        this.sequence = sequence;
        this.type = type;
        this.name = name;
        this.inputSummary = inputSummary;
        this.toolCallId = toolCallId;
        this.startedAt = startedAt;
        this.status = Status.RUNNING;
    }

    public static RunStep start(String runId,
                                int sequence,
                                Type type,
                                String name,
                                String inputSummary,
                                String toolCallId) {
        return new RunStep(
                ProtocolIds.next("step"), runId, sequence, type, name,
                inputSummary, toolCallId, ProtocolIds.now()
        );
    }

    public void complete(String output) {
        requireRunning("complete");
        status = Status.COMPLETED;
        outputSummary = output;
        completedAt = ProtocolIds.now();
    }

    public void block(String reason) {
        requireRunning("block");
        status = Status.BLOCKED;
        error = reason;
        completedAt = ProtocolIds.now();
    }

    public void fail(String message) {
        if (status != Status.RUNNING) {
            return;
        }
        status = Status.FAILED;
        error = message;
        completedAt = ProtocolIds.now();
    }

    private void requireRunning(String action) {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("Cannot " + action + " Step " + id + " while status is " + status);
        }
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public int getSequence() { return sequence; }
    public Type getType() { return type; }
    public String getName() { return name; }
    public String getInputSummary() { return inputSummary; }
    public String getToolCallId() { return toolCallId; }
    public String getStartedAt() { return startedAt; }
    public String getCompletedAt() { return completedAt; }
    public Status getStatus() { return status; }
    public String getOutputSummary() { return outputSummary; }
    public String getError() { return error; }
    public int getSchemaVersion() { return 1; }
}
