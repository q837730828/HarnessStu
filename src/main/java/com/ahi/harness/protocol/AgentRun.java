package com.ahi.harness.protocol;

/**
 * One manageable execution inside a Thread.
 *
 * A Run is the boundary for status, errors, checkpoints, interrupts and final
 * artifacts. Keeping this separate from Conversation is what makes execution
 * observable rather than just "more messages were appended".
 */
public class AgentRun {
    public enum Status {
        CREATED,
        RUNNING,
        INPUT_REQUIRED,
        AUTH_REQUIRED,
        COMPLETED,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }

    private final String id;
    private final String traceId;
    private final String threadId;
    private final String agentId;
    private final String input;
    private final String createdAt;
    private final int maxModelCalls;
    private final int timeoutSeconds;
    private final String parentRunId;
    private final String resumedFromCheckpointId;
    private int modelCalls;
    private String deadlineAt;
    private String startedAt;
    private String completedAt;
    private String updatedAt;
    private Status status;
    private String currentStepId;
    private String activeInterruptId;
    private String resultArtifactId;
    private String error;

    private AgentRun(String id,
                     String traceId,
                     String threadId,
                     String agentId,
                     String input,
                     String createdAt,
                     int maxModelCalls,
                     int timeoutSeconds,
                     String parentRunId,
                     String resumedFromCheckpointId) {
        this.id = id;
        this.traceId = traceId;
        this.threadId = threadId;
        this.agentId = agentId;
        this.input = input;
        this.createdAt = createdAt;
        this.maxModelCalls = maxModelCalls;
        this.timeoutSeconds = timeoutSeconds;
        this.parentRunId = parentRunId;
        this.resumedFromCheckpointId = resumedFromCheckpointId;
        this.updatedAt = createdAt;
        this.status = Status.CREATED;
    }

    public static AgentRun create(String threadId, String agentId, String input) {
        return create(threadId, agentId, input, 0);
    }

    public static AgentRun create(String threadId, String agentId, String input, int maxModelCalls) {
        return create(threadId, agentId, input, maxModelCalls, 0, null, null);
    }

    public static AgentRun create(String threadId,
                                  String agentId,
                                  String input,
                                  int maxModelCalls,
                                  int timeoutSeconds,
                                  String parentRunId,
                                  String resumedFromCheckpointId) {
        return new AgentRun(
                ProtocolIds.next("run"), ProtocolIds.next("trace"),
                threadId, agentId, input, ProtocolIds.now(), maxModelCalls,
                timeoutSeconds, parentRunId, resumedFromCheckpointId
        );
    }

    /** Rebuild a persisted Run for query/control without replaying transitions. */
    public static AgentRun rehydrate(String id,
                                     String traceId,
                                     String threadId,
                                     String agentId,
                                     String input,
                                     String createdAt,
                                     int maxModelCalls,
                                     int timeoutSeconds,
                                     String parentRunId,
                                     String resumedFromCheckpointId,
                                     int modelCalls,
                                     String deadlineAt,
                                     String startedAt,
                                     String completedAt,
                                     String updatedAt,
                                     Status status,
                                     String currentStepId,
                                     String activeInterruptId,
                                     String resultArtifactId,
                                     String error) {
        AgentRun run = new AgentRun(
                id, traceId, threadId, agentId, input, createdAt,
                maxModelCalls, timeoutSeconds, parentRunId, resumedFromCheckpointId
        );
        run.modelCalls = modelCalls;
        run.deadlineAt = deadlineAt;
        run.startedAt = startedAt;
        run.completedAt = completedAt;
        run.updatedAt = updatedAt;
        run.status = status;
        run.currentStepId = currentStepId;
        run.activeInterruptId = activeInterruptId;
        run.resultArtifactId = resultArtifactId;
        run.error = error;
        return run;
    }

    public void start() {
        require(Status.CREATED, "start");
        status = Status.RUNNING;
        startedAt = ProtocolIds.now();
        if (timeoutSeconds > 0) {
            deadlineAt = ProtocolIds.afterSeconds(timeoutSeconds);
        }
        updatedAt = startedAt;
    }

    public void beginStep(String stepId) {
        require(Status.RUNNING, "begin a step");
        currentStepId = stepId;
        updatedAt = ProtocolIds.now();
    }

    public void recordModelCall() {
        require(Status.RUNNING, "record a model call");
        if (maxModelCalls > 0 && modelCalls >= maxModelCalls) {
            throw new IllegalStateException("Run model-call budget exhausted: " + modelCalls + "/" + maxModelCalls);
        }
        modelCalls++;
        updatedAt = ProtocolIds.now();
    }

    public void finishStep(String stepId) {
        if (stepId != null && stepId.equals(currentStepId)) {
            currentStepId = null;
        }
        updatedAt = ProtocolIds.now();
    }

    public void requireInput(String interruptId) {
        require(Status.RUNNING, "request input");
        status = Status.INPUT_REQUIRED;
        activeInterruptId = interruptId;
        updatedAt = ProtocolIds.now();
    }

    public void requireAuthorization(String interruptId) {
        require(Status.RUNNING, "request authorization");
        status = Status.AUTH_REQUIRED;
        activeInterruptId = interruptId;
        updatedAt = ProtocolIds.now();
    }

    public void resume() {
        if (status != Status.INPUT_REQUIRED && status != Status.AUTH_REQUIRED) {
            throw new IllegalStateException("Cannot resume Run " + id + " while status is " + status);
        }
        status = Status.RUNNING;
        activeInterruptId = null;
        updatedAt = ProtocolIds.now();
    }

    public void complete(String artifactId) {
        require(Status.RUNNING, "complete");
        status = Status.COMPLETED;
        resultArtifactId = artifactId;
        currentStepId = null;
        completedAt = ProtocolIds.now();
        updatedAt = completedAt;
    }

    public void fail(String message) {
        if (isTerminal()) {
            return;
        }
        status = Status.FAILED;
        error = message;
        currentStepId = null;
        completedAt = ProtocolIds.now();
        updatedAt = completedAt;
    }

    public void cancel(String reason) {
        if (isTerminal()) {
            return;
        }
        status = Status.CANCELLED;
        error = reason;
        currentStepId = null;
        completedAt = ProtocolIds.now();
        updatedAt = completedAt;
    }

    public void timeOut(String reason) {
        if (isTerminal()) {
            return;
        }
        status = Status.TIMED_OUT;
        error = reason;
        currentStepId = null;
        completedAt = ProtocolIds.now();
        updatedAt = completedAt;
    }

    public boolean isTimedOut() {
        return !isTerminal() && ProtocolIds.isPast(deadlineAt);
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED
                || status == Status.CANCELLED || status == Status.TIMED_OUT;
    }

    private void require(Status expected, String action) {
        if (status != expected) {
            throw new IllegalStateException("Cannot " + action + " Run " + id + " while status is " + status);
        }
    }

    public String getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getThreadId() { return threadId; }
    public String getAgentId() { return agentId; }
    public String getInput() { return input; }
    public String getCreatedAt() { return createdAt; }
    public String getStartedAt() { return startedAt; }
    public String getCompletedAt() { return completedAt; }
    public String getUpdatedAt() { return updatedAt; }
    public Status getStatus() { return status; }
    public String getCurrentStepId() { return currentStepId; }
    public String getActiveInterruptId() { return activeInterruptId; }
    public String getResultArtifactId() { return resultArtifactId; }
    public String getError() { return error; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public int getModelCalls() { return modelCalls; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public String getDeadlineAt() { return deadlineAt; }
    public String getParentRunId() { return parentRunId; }
    public String getResumedFromCheckpointId() { return resumedFromCheckpointId; }
    public int getSchemaVersion() { return 1; }
}
