package com.ahi.harness.core;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.hooks.HookBus;
import com.ahi.harness.model.ModelClient;
import com.ahi.harness.permission.PermissionDecision;
import com.ahi.harness.permission.PermissionPolicy;
import com.ahi.harness.permission.PermissionPrompter;
import com.ahi.harness.permission.PermissionStore;
import com.ahi.harness.protocol.AgentIdentity;
import com.ahi.harness.protocol.AgentMessage;
import com.ahi.harness.protocol.AgentRun;
import com.ahi.harness.protocol.AgentThread;
import com.ahi.harness.protocol.Artifact;
import com.ahi.harness.protocol.Checkpoint;
import com.ahi.harness.protocol.CancellationRequest;
import com.ahi.harness.protocol.Interrupt;
import com.ahi.harness.protocol.ProtocolError;
import com.ahi.harness.protocol.RunStep;
import com.ahi.harness.protocol.RuntimeEvent;
import com.ahi.harness.protocol.TraceSpan;
import com.ahi.harness.runtime.RunRecorder;
import com.ahi.harness.runtime.RuntimeStore;
import com.ahi.harness.session.NoopSessionStore;
import com.ahi.harness.tools.Tool;
import com.ahi.harness.tools.ToolRegistry;
import com.ahi.harness.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentLoopProtocolTest {
    @Test
    public void loopPersistsRunStepsCheckpointAndFinalArtifact() throws Exception {
        RecordingStore store = new RecordingStore();
        ToolRegistry tools = new ToolRegistry();
        RunRecorder recorder = recorder(store, tools);
        ModelClient model = new ModelClient() {
            public ModelResponse generate(Conversation conversation, ToolRegistry registry) {
                return new ModelResponse("done", null, Collections.<ToolCall>emptyList());
            }
        };
        AgentLoop loop = loop(model, tools, new PermissionPolicy(), null, recorder);

        Conversation conversation = new Conversation();
        conversation.addSystem("system");
        loop.run(conversation, "hello");

        assertEquals(AgentRun.Status.COMPLETED, store.lastRun.getStatus());
        assertTrue(hasStep(store.steps, RunStep.Type.MODEL_CALL, RunStep.Status.COMPLETED));
        assertTrue(hasEvent(store.events, RuntimeEvent.Type.RUN_COMPLETED));
        assertTrue(hasEvent(store.events, RuntimeEvent.Type.MESSAGE_CREATED));
        assertTrue(hasEvent(store.events, RuntimeEvent.Type.TEXT_DELTA));
        assertEquals(Artifact.Type.FINAL_RESPONSE, store.artifacts.get(0).getType());
        assertTrue(store.checkpoints.size() > 0);
        assertTrue(store.checkpoints.get(0).getTraceId() != null);
        assertTrue(store.checkpoints.get(0).getSpanId() != null);
        assertEquals(2, store.messages.size());
        assertTrue(hasCompletedSpan(store.spans, TraceSpan.Kind.AGENT_RUN));
        assertTrue(hasCorrelatedStepEvent(store.events));
        assertEquals(1, store.lastRun.getModelCalls());
    }

    @Test
    public void approvalBecomesInterruptAndToolExceptionBecomesData() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        RecordingStore store = new RecordingStore();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new Tool() {
            public String name() { return "edit_file"; }
            public String description() { return "test mutation"; }
            public ObjectNode parameters() {
                ObjectNode schema = mapper.createObjectNode();
                schema.put("type", "object");
                schema.set("properties", mapper.createObjectNode());
                return schema;
            }
            public ToolResult execute(JsonNode arguments) {
                throw new IllegalStateException("simulated tool failure");
            }
        });

        final ToolCall call = new ToolCall(
                "call-1",
                "edit_file",
                mapper.readTree("{\"path\":\"a.txt\",\"old_text\":\"a\",\"new_text\":\"b\"}"),
                "{\"path\":\"a.txt\",\"old_text\":\"a\",\"new_text\":\"b\"}"
        );
        ModelClient model = new ModelClient() {
            private int calls;

            public ModelResponse generate(Conversation conversation, ToolRegistry registry) {
                calls++;
                if (calls == 1) {
                    return new ModelResponse("", null, Arrays.asList(call));
                }
                Message observation = conversation.messages().get(conversation.messages().size() - 1);
                assertTrue(observation.content().contains("ERROR"));
                assertTrue(observation.content().contains("simulated tool failure"));
                return new ModelResponse("recovered", null, Collections.<ToolCall>emptyList());
            }
        };
        File workspace = Files.createTempDirectory("harness-permission").toFile();
        ConsoleLog log = new ConsoleLog();
        PermissionPrompter prompter = new PermissionPrompter(log, new PermissionStore(workspace)) {
            @Override
            public PermissionDecision resolve(ToolCall toolCall, PermissionDecision decision) {
                return PermissionDecision.allow("approved by test");
            }
        };
        PermissionPolicy policy = new PermissionPolicy(
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                "ask"
        );
        RunRecorder recorder = recorder(store, tools);
        AgentLoop loop = loop(model, tools, policy, prompter, recorder);

        Conversation conversation = new Conversation();
        conversation.addSystem("system");
        loop.run(conversation, "edit");

        assertEquals(AgentRun.Status.COMPLETED, store.lastRun.getStatus());
        assertTrue(hasEvent(store.events, RuntimeEvent.Type.RUN_INPUT_REQUIRED));
        assertTrue(hasEvent(store.events, RuntimeEvent.Type.RUN_RESUMED));
        assertTrue(hasStep(store.steps, RunStep.Type.TOOL_CALL, RunStep.Status.FAILED));
        assertEquals(Interrupt.Status.RESOLVED, store.interrupts.get(store.interrupts.size() - 1).getStatus());
        assertTrue(hasError(store.errors, ProtocolError.Source.TOOL));
        assertTrue(hasCompletedSpan(store.spans, TraceSpan.Kind.TOOL));
        assertTrue(store.checkpoints.size() > 1);
        assertEquals(store.checkpoints.get(0).getId(),
                store.checkpoints.get(1).getParentCheckpointId());
    }

    @Test
    public void durableCancellationStopsBeforeNextModelBoundary() throws Exception {
        RecordingStore store = new RecordingStore();
        store.cancelAllRuns = true;
        ToolRegistry tools = new ToolRegistry();
        ModelClient model = new ModelClient() {
            public ModelResponse generate(Conversation conversation, ToolRegistry registry) {
                throw new AssertionError("model must not be called after cancellation");
            }
        };
        AgentLoop loop = loop(model, tools, new PermissionPolicy(), null, recorder(store, tools));
        Conversation conversation = new Conversation();
        conversation.addSystem("system");

        loop.run(conversation, "cancel me");

        assertEquals(AgentRun.Status.CANCELLED, store.lastRun.getStatus());
        assertTrue(hasEvent(store.events, RuntimeEvent.Type.RUN_CANCELLED));
    }

    @Test
    public void cancellationRaisedDuringModelCallWinsAtNextSafeBoundary() throws Exception {
        final RecordingStore store = new RecordingStore();
        ToolRegistry tools = new ToolRegistry();
        ModelClient model = new ModelClient() {
            public ModelResponse generate(Conversation conversation, ToolRegistry registry) {
                store.cancelAllRuns = true;
                return new ModelResponse("must not become final", null, Collections.<ToolCall>emptyList());
            }
        };
        AgentLoop loop = loop(model, tools, new PermissionPolicy(), null, recorder(store, tools));
        Conversation conversation = new Conversation();
        conversation.addSystem("system");

        loop.run(conversation, "cancel while model runs");

        assertEquals(AgentRun.Status.CANCELLED, store.lastRun.getStatus());
        assertTrue(hasEvent(store.events, RuntimeEvent.Type.RUN_CANCELLED));
    }

    @Test
    public void checkpointRecoveryCreatesANewAuditableBranch() throws Exception {
        RecordingStore store = new RecordingStore();
        ToolRegistry tools = new ToolRegistry();
        ModelClient model = new ModelClient() {
            public ModelResponse generate(Conversation conversation, ToolRegistry registry) {
                return new ModelResponse("continued", null, Collections.<ToolCall>emptyList());
            }
        };
        Conversation conversation = new Conversation();
        conversation.addSystem("restored system");
        conversation.markRecoveredFrom("run-parent", "checkpoint-parent");

        loop(model, tools, new PermissionPolicy(), null, recorder(store, tools))
                .run(conversation, "continue");

        assertEquals("run-parent", store.lastRun.getParentRunId());
        assertEquals("checkpoint-parent", store.lastRun.getResumedFromCheckpointId());
        assertEquals("checkpoint-parent", store.checkpoints.get(0).getParentCheckpointId());
    }

    private AgentLoop loop(ModelClient model,
                           ToolRegistry tools,
                           PermissionPolicy policy,
                           PermissionPrompter prompter,
                           RunRecorder recorder) {
        return new AgentLoop(
                model, tools, policy, new NoopSessionStore(), new ConsoleLog(),
                new HookBus(new ConsoleLog()), prompter, null, null, null,
                4, 0, 4, 0, recorder
        );
    }

    private RunRecorder recorder(RecordingStore store, ToolRegistry tools) throws Exception {
        List<String> names = new ArrayList<String>();
        for (Tool tool : tools.all()) {
            names.add(tool.name());
        }
        return new RunRecorder(
                AgentIdentity.create("test", "test-model", names),
                AgentThread.create("test-thread"),
                store
        );
    }

    private boolean hasEvent(List<RuntimeEvent> events, RuntimeEvent.Type type) {
        for (RuntimeEvent event : events) {
            if (event.getType() == type) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCorrelatedStepEvent(List<RuntimeEvent> events) {
        for (RuntimeEvent event : events) {
            if (event.getStepId() != null
                    && event.getTraceId() != null
                    && event.getSpanId() != null) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStep(List<RunStep> steps, RunStep.Type type, RunStep.Status status) {
        for (RunStep step : steps) {
            if (step.getType() == type && step.getStatus() == status) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCompletedSpan(List<TraceSpan> spans, TraceSpan.Kind kind) {
        for (TraceSpan span : spans) {
            if (span.getKind() == kind && span.getStatus() != TraceSpan.Status.RUNNING) {
                return true;
            }
        }
        return false;
    }

    private boolean hasError(List<ProtocolError> errors, ProtocolError.Source source) {
        for (ProtocolError error : errors) {
            if (error.getSource() == source) {
                return true;
            }
        }
        return false;
    }

    private static class RecordingStore implements RuntimeStore {
        private AgentRun lastRun;
        private final List<RunStep> steps = new ArrayList<RunStep>();
        private final List<RuntimeEvent> events = new ArrayList<RuntimeEvent>();
        private final List<Artifact> artifacts = new ArrayList<Artifact>();
        private final List<Checkpoint> checkpoints = new ArrayList<Checkpoint>();
        private final List<Interrupt> interrupts = new ArrayList<Interrupt>();
        private final List<AgentMessage> messages = new ArrayList<AgentMessage>();
        private final List<TraceSpan> spans = new ArrayList<TraceSpan>();
        private final List<ProtocolError> errors = new ArrayList<ProtocolError>();
        private CancellationRequest cancellationRequest;
        private boolean cancelAllRuns;

        public void saveAgent(AgentIdentity agent) { }
        public void saveThread(AgentThread thread) { }
        public void saveRun(AgentRun run) { lastRun = run; }
        public void appendStep(RunStep step) { steps.add(step); }
        public void appendEvent(RuntimeEvent event) { events.add(event); }
        public void appendMessage(AgentMessage message) { messages.add(message); }
        public void appendArtifact(Artifact artifact) { artifacts.add(artifact); }
        public void saveCheckpoint(Checkpoint checkpoint, List<Message> messages) { checkpoints.add(checkpoint); }
        public void appendInterrupt(Interrupt interrupt) { interrupts.add(interrupt); }
        public void appendSpan(TraceSpan span) { spans.add(span); }
        public void appendError(ProtocolError error) { errors.add(error); }
        public List<AgentRun> listRuns() {
            return lastRun == null ? Collections.<AgentRun>emptyList() : Arrays.asList(lastRun);
        }
        public AgentRun loadRun(String runId) { return lastRun != null && lastRun.getId().equals(runId) ? lastRun : null; }
        public List<RuntimeEvent> readEvents(String runId, long afterSequence) {
            List<RuntimeEvent> found = new ArrayList<RuntimeEvent>();
            for (RuntimeEvent event : events) {
                if (event.getRunId().equals(runId) && event.getSequence() > afterSequence) {
                    found.add(event);
                }
            }
            return found;
        }
        public void saveCancellationRequest(CancellationRequest request) { cancellationRequest = request; }
        public CancellationRequest findCancellationRequest(String runId) {
            if (cancelAllRuns) {
                return new CancellationRequest(runId, "cancelled by test");
            }
            return cancellationRequest != null && cancellationRequest.getRunId().equals(runId)
                    ? cancellationRequest : null;
        }
        public List<Message> loadCheckpointMessages(String runId, String checkpointId) {
            return Collections.emptyList();
        }
    }
}
