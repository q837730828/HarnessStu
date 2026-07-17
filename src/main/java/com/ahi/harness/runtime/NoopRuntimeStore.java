package com.ahi.harness.runtime;

import com.ahi.harness.core.Message;
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

import java.util.Collections;
import java.util.List;

/** No-op adapter used by old constructors and isolated tests. */
public class NoopRuntimeStore implements RuntimeStore {
    public void saveAgent(AgentIdentity agent) { }
    public void saveThread(AgentThread thread) { }
    public void saveRun(AgentRun run) { }
    public void appendStep(RunStep step) { }
    public void appendEvent(RuntimeEvent event) { }
    public void appendMessage(AgentMessage message) { }
    public void appendArtifact(Artifact artifact) { }
    public void saveCheckpoint(Checkpoint checkpoint, List<Message> messages) { }
    public void appendInterrupt(Interrupt interrupt) { }
    public void appendSpan(TraceSpan span) { }
    public void appendError(ProtocolError error) { }
    public List<AgentRun> listRuns() { return Collections.emptyList(); }
    public AgentRun loadRun(String runId) { return null; }
    public List<RuntimeEvent> readEvents(String runId, long afterSequence) { return Collections.emptyList(); }
    public void saveCancellationRequest(CancellationRequest request) { }
    public CancellationRequest findCancellationRequest(String runId) { return null; }

    public List<Message> loadCheckpointMessages(String runId, String checkpointId) {
        return Collections.emptyList();
    }
}
