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

import java.util.List;

/**
 * Persistence boundary for explicit Agent Protocol resources.
 *
 * The loop depends on this interface rather than JSON files. A future database,
 * REST service or event broker can implement the same contract without changing
 * the protocol models or the AgentLoop.
 */
public interface RuntimeStore {
    void saveAgent(AgentIdentity agent) throws Exception;

    void saveThread(AgentThread thread) throws Exception;

    void saveRun(AgentRun run) throws Exception;

    void appendStep(RunStep step) throws Exception;

    void appendEvent(RuntimeEvent event) throws Exception;

    void appendMessage(AgentMessage message) throws Exception;

    void appendArtifact(Artifact artifact) throws Exception;

    void saveCheckpoint(Checkpoint checkpoint, List<Message> messages) throws Exception;

    void appendInterrupt(Interrupt interrupt) throws Exception;

    void appendSpan(TraceSpan span) throws Exception;

    void appendError(ProtocolError error) throws Exception;

    List<AgentRun> listRuns() throws Exception;

    AgentRun loadRun(String runId) throws Exception;

    List<RuntimeEvent> readEvents(String runId, long afterSequence) throws Exception;

    void saveCancellationRequest(CancellationRequest request) throws Exception;

    CancellationRequest findCancellationRequest(String runId) throws Exception;

    List<Message> loadCheckpointMessages(String runId, String checkpointId) throws Exception;
}
