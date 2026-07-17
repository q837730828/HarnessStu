package com.ahi.harness.runtime;

import com.ahi.harness.core.Conversation;
import com.ahi.harness.core.Message;
import com.ahi.harness.core.ToolCall;
import com.ahi.harness.protocol.AgentIdentity;
import com.ahi.harness.protocol.AgentMessage;
import com.ahi.harness.protocol.AgentRun;
import com.ahi.harness.protocol.AgentThread;
import com.ahi.harness.protocol.Artifact;
import com.ahi.harness.protocol.Checkpoint;
import com.ahi.harness.protocol.CancellationRequest;
import com.ahi.harness.protocol.Interrupt;
import com.ahi.harness.protocol.MessagePart;
import com.ahi.harness.protocol.ProtocolError;
import com.ahi.harness.protocol.RunStep;
import com.ahi.harness.protocol.RuntimeEvent;
import com.ahi.harness.protocol.TraceSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates AgentLoop actions into explicit protocol resources.
 *
 * This class is intentionally a thin lifecycle service: domain objects enforce
 * their state transitions, RuntimeStore persists them, and AgentLoop stays
 * focused on model/tool orchestration.
 */
public class RunRecorder {
    private final AgentIdentity agent;
    private final AgentThread thread;
    private final RuntimeStore store;
    private final Map<String, Counters> counters = new HashMap<String, Counters>();
    private final Map<String, TraceSpan> runSpans = new HashMap<String, TraceSpan>();
    private final Map<String, TraceSpan> stepSpans = new HashMap<String, TraceSpan>();
    private final Map<String, String> runSpanIds = new HashMap<String, String>();
    private final Map<String, String> stepSpanIds = new HashMap<String, String>();

    public RunRecorder(AgentIdentity agent, AgentThread thread, RuntimeStore store) throws Exception {
        this.agent = agent;
        this.thread = thread;
        this.store = store;
        thread.addParticipant(agent.getId());
        for (String capability : agent.getCapabilities()) {
            thread.addCapability(capability);
        }
        store.saveAgent(agent);
        store.saveThread(thread);
    }

    public static RunRecorder noop() {
        try {
            return new RunRecorder(
                    AgentIdentity.create("legacy-harness", "unspecified", Collections.<String>emptyList()),
                    AgentThread.create("legacy-thread"),
                    new NoopRuntimeStore()
            );
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public AgentRun beginRun(String input) throws Exception {
        return beginRun(input, 0);
    }

    public AgentRun beginRun(String input, int maxModelCalls) throws Exception {
        return beginRun(input, maxModelCalls, 0, null, null);
    }

    public AgentRun beginRun(String input,
                             int maxModelCalls,
                             int timeoutSeconds,
                             String parentRunId,
                             String resumedFromCheckpointId) throws Exception {
        thread.touch();
        store.saveThread(thread);
        AgentRun run = AgentRun.create(
                thread.getId(), agent.getId(), input, maxModelCalls,
                timeoutSeconds, parentRunId, resumedFromCheckpointId
        );
        Counters runCounters = new Counters();
        // A resumed Run starts a new checkpoint branch from the loaded source.
        runCounters.lastCheckpointId = resumedFromCheckpointId;
        counters.put(run.getId(), runCounters);
        store.saveRun(run);
        emit(run, null, RuntimeEvent.Type.RUN_CREATED, "Run resource created");
        run.start();
        TraceSpan runSpan = new TraceSpan(
                run.getTraceId(), null, run.getId(), null,
                TraceSpan.Kind.AGENT_RUN, "agent.run"
        );
        runSpans.put(run.getId(), runSpan);
        runSpanIds.put(run.getId(), runSpan.getId());
        store.appendSpan(runSpan);
        store.saveRun(run);
        emit(run, null, RuntimeEvent.Type.RUN_STARTED, "Run execution started");
        return run;
    }

    public RunStep startStep(AgentRun run,
                             RunStep.Type type,
                             String name,
                             String inputSummary,
                             String toolCallId) throws Exception {
        Counters value = counters(run);
        if (type == RunStep.Type.MODEL_CALL) {
            run.recordModelCall();
        }
        RunStep step = RunStep.start(run.getId(), ++value.step, type, name, inputSummary, toolCallId);
        run.beginStep(step.getId());
        TraceSpan parent = runSpans.get(run.getId());
        TraceSpan span = new TraceSpan(
                run.getTraceId(), parent == null ? null : parent.getId(),
                run.getId(), step.getId(), spanKind(type), name
        );
        stepSpans.put(step.getId(), span);
        stepSpanIds.put(step.getId(), span.getId());
        value.stepIds.add(step.getId());
        store.saveRun(run);
        store.appendStep(step);
        store.appendSpan(span);
        emit(run, step.getId(), RuntimeEvent.Type.STEP_STARTED, type + ": " + name);
        return step;
    }

    public void completeStep(AgentRun run, RunStep step, String outputSummary) throws Exception {
        step.complete(outputSummary);
        run.finishStep(step.getId());
        store.appendStep(step);
        store.saveRun(run);
        finishStepSpan(step, true, null);
        emit(run, step.getId(), RuntimeEvent.Type.STEP_COMPLETED, outputSummary);
    }

    public void blockStep(AgentRun run, RunStep step, String reason) throws Exception {
        step.block(reason);
        run.finishStep(step.getId());
        store.appendStep(step);
        store.saveRun(run);
        finishStepSpan(step, false, reason);
        store.appendError(new ProtocolError(
                run.getTraceId(), run.getId(), step.getId(),
                ProtocolError.Source.PERMISSION, "STEP_BLOCKED", reason, false
        ));
        emit(run, step.getId(), RuntimeEvent.Type.STEP_BLOCKED, reason);
    }

    public void failStep(AgentRun run, RunStep step, String error) throws Exception {
        failStep(run, step, error, ProtocolError.Source.RUNTIME, false);
    }

    public void failStep(AgentRun run,
                         RunStep step,
                         String error,
                         ProtocolError.Source source,
                         boolean retryable) throws Exception {
        if (step == null || step.getStatus() != RunStep.Status.RUNNING) {
            return;
        }
        step.fail(error);
        run.finishStep(step.getId());
        store.appendStep(step);
        store.saveRun(run);
        finishStepSpan(step, false, error);
        store.appendError(new ProtocolError(
                run.getTraceId(), run.getId(), step.getId(),
                source, "STEP_FAILED", error, retryable
        ));
        emit(run, step.getId(), RuntimeEvent.Type.STEP_FAILED, error);
    }

    /** Convert provider-facing Message into stable Message/Part protocol data. */
    public void message(AgentRun run, RunStep step, Message message) throws Exception {
        List<MessagePart> parts = new ArrayList<MessagePart>();
        if ("tool".equals(message.role())) {
            parts.add(MessagePart.toolResult(message.toolCallId(), message.content()));
        } else if (message.content() != null && !message.content().isEmpty()) {
            parts.add(MessagePart.text(message.content()));
        }
        for (ToolCall call : message.toolCalls()) {
            parts.add(MessagePart.toolCall(call.id(), call.name(), call.argumentsJson()));
        }
        AgentMessage protocolMessage = new AgentMessage(
                run.getThreadId(), run.getId(), step == null ? null : step.getId(),
                role(message.role()), parts
        );
        store.appendMessage(protocolMessage);
        emit(run, protocolMessage.getStepId(), RuntimeEvent.Type.MESSAGE_CREATED,
                "message_id=" + protocolMessage.getId() + ", role=" + protocolMessage.getRole());
        // The current model adapter is non-streaming, so one complete response
        // is represented as one TEXT_DELTA. A streaming adapter can call the
        // same event type once per chunk without changing the protocol.
        if (protocolMessage.getRole() == AgentMessage.Role.ASSISTANT
                && message.content() != null && !message.content().isEmpty()) {
            emit(run, protocolMessage.getStepId(), RuntimeEvent.Type.TEXT_DELTA, message.content());
        }
        for (ToolCall call : message.toolCalls()) {
            emit(run, protocolMessage.getStepId(), RuntimeEvent.Type.TOOL_CALL_REQUESTED,
                    "tool_call_id=" + call.id() + ", name=" + call.name());
        }
        if (protocolMessage.getRole() == AgentMessage.Role.TOOL) {
            emit(run, protocolMessage.getStepId(), RuntimeEvent.Type.TOOL_RESULT_APPENDED,
                    "tool_call_id=" + message.toolCallId());
        }
    }

    public void permission(AgentRun run, RunStep step, String detail) throws Exception {
        emit(run, step == null ? null : step.getId(), RuntimeEvent.Type.PERMISSION_DECIDED, detail);
    }

    public Checkpoint checkpoint(AgentRun run,
                                 RunStep step,
                                 Conversation conversation,
                                 String reason) throws Exception {
        Counters value = counters(run);
        Checkpoint checkpoint = new Checkpoint(
                run.getThreadId(),
                run.getId(),
                step == null ? null : step.getId(),
                run.getTraceId(),
                step == null ? runSpanIds.get(run.getId()) : stepSpanIds.get(step.getId()),
                value.lastCheckpointId,
                ++value.checkpoint,
                reason,
                conversation.size(),
                conversation.estimatedTokens(),
                true
        );
        store.saveCheckpoint(checkpoint, conversation.messages());
        value.lastCheckpointId = checkpoint.getId();
        emit(run, checkpoint.getStepId(), RuntimeEvent.Type.CHECKPOINT_CREATED, checkpoint.getId() + ": " + reason);
        return checkpoint;
    }

    public Interrupt openInterrupt(AgentRun run,
                                   RunStep step,
                                   Interrupt.Type type,
                                   String prompt,
                                   String payload) throws Exception {
        Interrupt interrupt = new Interrupt(
                run.getThreadId(), run.getId(), step == null ? null : step.getId(),
                type, prompt, payload
        );
        store.appendInterrupt(interrupt);
        if (type == Interrupt.Type.AUTH_REQUIRED) {
            run.requireAuthorization(interrupt.getId());
        } else {
            run.requireInput(interrupt.getId());
        }
        store.saveRun(run);
        emit(run, interrupt.getStepId(), RuntimeEvent.Type.INTERRUPT_CREATED, interrupt.getId() + ": " + prompt);
        emit(run, interrupt.getStepId(),
                type == Interrupt.Type.AUTH_REQUIRED
                        ? RuntimeEvent.Type.RUN_AUTH_REQUIRED : RuntimeEvent.Type.RUN_INPUT_REQUIRED,
                interrupt.getId());
        return interrupt;
    }

    public void resolveInterrupt(AgentRun run, Interrupt interrupt, boolean allowed, String resolution) throws Exception {
        if (allowed) {
            interrupt.resolve(resolution);
        } else {
            interrupt.reject(resolution);
        }
        store.appendInterrupt(interrupt);
        run.resume();
        store.saveRun(run);
        emit(run, interrupt.getStepId(), RuntimeEvent.Type.INTERRUPT_RESOLVED,
                interrupt.getId() + ": " + interrupt.getStatus());
        emit(run, interrupt.getStepId(), RuntimeEvent.Type.RUN_RESUMED, "Input supplied to Run");
    }

    public Artifact artifact(AgentRun run,
                             RunStep step,
                             Artifact.Type type,
                             String name,
                             String mediaType,
                             String uri,
                             String content,
                             long contentLength) throws Exception {
        Artifact artifact = new Artifact(
                run.getId(), step == null ? null : step.getId(), type,
                name, mediaType, uri, content, contentLength
        );
        store.appendArtifact(artifact);
        emit(run, artifact.getStepId(), RuntimeEvent.Type.ARTIFACT_CREATED,
                artifact.getId() + ": " + name);
        return artifact;
    }

    public void completeRun(AgentRun run, String finalAnswer) throws Exception {
        Artifact result = artifact(
                run, null, Artifact.Type.FINAL_RESPONSE, "final-response.txt",
                "text/plain; charset=utf-8", null, finalAnswer,
                finalAnswer == null ? 0 : finalAnswer.length()
        );
        run.complete(result.getId());
        store.saveRun(run);
        finishRunSpan(run, true, null);
        emit(run, null, RuntimeEvent.Type.RUN_COMPLETED, "result_artifact_id=" + result.getId());
        releaseRunState(run.getId());
    }

    public void failRun(AgentRun run, String error) throws Exception {
        if (run.isTerminal()) {
            return;
        }
        run.fail(error);
        store.saveRun(run);
        store.appendError(new ProtocolError(
                run.getTraceId(), run.getId(), null,
                ProtocolError.Source.RUNTIME, "RUN_FAILED", error, false
        ));
        finishRunSpan(run, false, error);
        emit(run, null, RuntimeEvent.Type.RUN_FAILED, error);
        releaseRunState(run.getId());
    }

    public void cancelRun(AgentRun run, String reason) throws Exception {
        if (run.isTerminal()) {
            return;
        }
        run.cancel(reason);
        store.saveRun(run);
        TraceSpan span = runSpans.remove(run.getId());
        if (span != null) {
            span.cancel(reason);
            store.appendSpan(span);
        }
        emit(run, null, RuntimeEvent.Type.RUN_CANCELLED, reason);
        releaseRunState(run.getId());
    }

    public void timeOutRun(AgentRun run, String reason) throws Exception {
        if (run.isTerminal()) {
            return;
        }
        run.timeOut(reason);
        store.saveRun(run);
        store.appendError(new ProtocolError(
                run.getTraceId(), run.getId(), null,
                ProtocolError.Source.RUNTIME, "RUN_TIMED_OUT", reason, true
        ));
        finishRunSpan(run, false, reason);
        emit(run, null, RuntimeEvent.Type.RUN_TIMED_OUT, reason);
        releaseRunState(run.getId());
    }

    public RuntimeStore store() {
        return store;
    }

    public boolean cancelIfRequested(AgentRun run) throws Exception {
        CancellationRequest request = store.findCancellationRequest(run.getId());
        if (request == null || run.isTerminal()) {
            return false;
        }
        cancelRun(run, request.getReason());
        return true;
    }

    public void requestCancellation(String runId, String reason) throws Exception {
        store.saveCancellationRequest(new CancellationRequest(runId, reason));
    }

    private void emit(AgentRun run, String stepId, RuntimeEvent.Type type, String detail) throws Exception {
        Counters value = counters(run);
        store.appendEvent(new RuntimeEvent(
                run.getThreadId(), run.getId(), stepId,
                run.getTraceId(), stepId == null ? runSpanIds.get(run.getId()) : stepSpanIds.get(stepId),
                ++value.event, type, detail
        ));
    }

    private Counters counters(AgentRun run) {
        Counters value = counters.get(run.getId());
        if (value == null) {
            value = new Counters();
            counters.put(run.getId(), value);
        }
        return value;
    }

    private AgentMessage.Role role(String value) {
        if ("system".equals(value)) {
            return AgentMessage.Role.SYSTEM;
        }
        if ("user".equals(value)) {
            return AgentMessage.Role.USER;
        }
        if ("tool".equals(value)) {
            return AgentMessage.Role.TOOL;
        }
        return AgentMessage.Role.ASSISTANT;
    }

    private TraceSpan.Kind spanKind(RunStep.Type type) {
        if (type == RunStep.Type.MODEL_CALL) {
            return TraceSpan.Kind.MODEL;
        }
        if (type == RunStep.Type.TOOL_CALL) {
            return TraceSpan.Kind.TOOL;
        }
        if (type == RunStep.Type.GUARDRAIL) {
            return TraceSpan.Kind.GUARDRAIL;
        }
        if (type == RunStep.Type.SUBAGENT_TASK) {
            return TraceSpan.Kind.SUBAGENT;
        }
        return TraceSpan.Kind.INTERNAL;
    }

    private void finishStepSpan(RunStep step, boolean success, String error) throws Exception {
        TraceSpan span = stepSpans.remove(step.getId());
        if (span == null) {
            return;
        }
        if (success) {
            span.succeed();
        } else {
            span.fail(error);
        }
        store.appendSpan(span);
    }

    private void finishRunSpan(AgentRun run, boolean success, String error) throws Exception {
        TraceSpan span = runSpans.remove(run.getId());
        if (span == null) {
            return;
        }
        if (success) {
            span.succeed();
        } else {
            span.fail(error);
        }
        store.appendSpan(span);
    }

    /** Release in-memory correlation indexes after the final event is stored. */
    private void releaseRunState(String runId) {
        Counters value = counters.remove(runId);
        runSpans.remove(runId);
        runSpanIds.remove(runId);
        if (value != null) {
            for (String stepId : value.stepIds) {
                stepSpans.remove(stepId);
                stepSpanIds.remove(stepId);
            }
        }
    }

    private static class Counters {
        private int step;
        private int checkpoint;
        private long event;
        private String lastCheckpointId;
        private final List<String> stepIds = new ArrayList<String>();
    }
}
