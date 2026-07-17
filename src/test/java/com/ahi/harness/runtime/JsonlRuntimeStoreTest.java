package com.ahi.harness.runtime;

import com.ahi.harness.core.Message;
import com.ahi.harness.protocol.AgentIdentity;
import com.ahi.harness.protocol.AgentMessage;
import com.ahi.harness.protocol.AgentRun;
import com.ahi.harness.protocol.AgentThread;
import com.ahi.harness.protocol.Checkpoint;
import com.ahi.harness.protocol.CancellationRequest;
import com.ahi.harness.protocol.MessagePart;
import com.ahi.harness.protocol.ProtocolError;
import com.ahi.harness.protocol.RuntimeEvent;
import com.ahi.harness.protocol.TraceSpan;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JsonlRuntimeStoreTest {
    @Test
    public void checkpointPersistsAndRestoresCompleteMessageSnapshot() throws Exception {
        File workspace = Files.createTempDirectory("harness-runtime-store").toFile();
        JsonlRuntimeStore store = new JsonlRuntimeStore(workspace);
        AgentIdentity agent = AgentIdentity.create("test-agent", "test-model", Arrays.asList("read_file"));
        AgentThread thread = AgentThread.create("test-thread");
        AgentRun run = AgentRun.create(
                thread.getId(), agent.getId(), "inspect", 6, 60,
                "parent-run", "source-checkpoint"
        );
        run.start();
        Checkpoint checkpoint = new Checkpoint(
                thread.getId(), run.getId(), null, 1,
                "test snapshot", 2, 10, true
        );

        store.saveAgent(agent);
        store.saveThread(thread);
        store.saveRun(run);
        store.appendMessage(new AgentMessage(
                thread.getId(), run.getId(), null, AgentMessage.Role.USER,
                Arrays.asList(MessagePart.text("inspect"))
        ));
        store.appendEvent(new RuntimeEvent(
                thread.getId(), run.getId(), null, 1,
                RuntimeEvent.Type.RUN_CREATED, "created"
        ));
        store.appendEvent(new RuntimeEvent(
                thread.getId(), run.getId(), null, 2,
                RuntimeEvent.Type.RUN_STARTED, "started"
        ));
        TraceSpan span = new TraceSpan(run.getTraceId(), null, run.getId(), null,
                TraceSpan.Kind.AGENT_RUN, "agent.run");
        store.appendSpan(span);
        span.succeed();
        store.appendSpan(span);
        store.appendError(new ProtocolError(
                run.getTraceId(), run.getId(), null,
                ProtocolError.Source.TOOL, "TEST", "recoverable", true
        ));
        CancellationRequest cancellation = new CancellationRequest(run.getId(), "stop test");
        store.saveCancellationRequest(cancellation);
        store.saveCheckpoint(checkpoint, Arrays.asList(Message.system("rules"), Message.user("inspect")));

        List<Message> restored = store.loadCheckpointMessages(run.getId(), checkpoint.getId());
        assertEquals(2, restored.size());
        assertEquals("system", restored.get(0).role());
        assertEquals("inspect", restored.get(1).content());
        assertTrue(new File(workspace, ".harness/runtime/runs/" + run.getId() + "/run.json").exists());
        assertTrue(new File(workspace, ".harness/runtime/runs/" + run.getId()
                + "/checkpoints/" + checkpoint.getId() + ".json").exists());
        assertEquals(run.getId(), store.loadRun(run.getId()).getId());
        assertEquals("parent-run", store.loadRun(run.getId()).getParentRunId());
        assertEquals("source-checkpoint", store.loadRun(run.getId()).getResumedFromCheckpointId());
        assertEquals(60, store.loadRun(run.getId()).getTimeoutSeconds());
        assertEquals(1, store.listRuns().size());
        assertEquals(1, store.readEvents(run.getId(), 1).size());
        assertEquals(2, store.readEvents(run.getId(), 1).get(0).getSequence());
        assertEquals("stop test", store.findCancellationRequest(run.getId()).getReason());
        assertTrue(new File(workspace, ".harness/runtime/runs/" + run.getId() + "/messages.jsonl").exists());
        assertTrue(new File(workspace, ".harness/runtime/runs/" + run.getId() + "/spans.jsonl").exists());
        assertTrue(new File(workspace, ".harness/runtime/runs/" + run.getId() + "/errors.jsonl").exists());
    }
}
