package com.ahi.harness.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProtocolStateTest {
    @Test
    public void runHasExplicitLifecycleIncludingInputRequired() {
        AgentRun run = AgentRun.create("thread-1", "agent-1", "do work");

        assertEquals(AgentRun.Status.CREATED, run.getStatus());
        run.start();
        run.beginStep("step-1");
        run.requireInput("interrupt-1");
        assertEquals(AgentRun.Status.INPUT_REQUIRED, run.getStatus());
        assertEquals("interrupt-1", run.getActiveInterruptId());

        run.resume();
        run.finishStep("step-1");
        run.complete("artifact-1");

        assertEquals(AgentRun.Status.COMPLETED, run.getStatus());
        assertEquals("artifact-1", run.getResultArtifactId());
        assertTrue(run.isTerminal());
    }

    @Test(expected = IllegalStateException.class)
    public void completedRunCannotStartAgain() {
        AgentRun run = AgentRun.create("thread-1", "agent-1", "do work");
        run.start();
        run.complete("artifact-1");
        run.start();
    }

    @Test
    public void interruptRecordsResolutionInsteadOfDisappearing() {
        Interrupt interrupt = new Interrupt(
                "thread-1", "run-1", "step-1",
                Interrupt.Type.TOOL_APPROVAL,
                "Approve edit", "{}"
        );

        interrupt.reject("user denied");

        assertEquals(Interrupt.Status.REJECTED, interrupt.getStatus());
        assertEquals("user denied", interrupt.getResolution());
    }

    @Test
    public void runCarriesRecoveryLineageAndHasExplicitTimeoutState() {
        AgentRun resumed = AgentRun.create(
                "thread-1", "agent-1", "continue", 8, 30,
                "run-parent", "checkpoint-stable"
        );
        assertEquals("run-parent", resumed.getParentRunId());
        assertEquals("checkpoint-stable", resumed.getResumedFromCheckpointId());
        assertEquals(30, resumed.getTimeoutSeconds());

        // Rehydrate a persisted deadline in the past so the timeout rule can
        // be tested deterministically without sleeping.
        AgentRun expired = AgentRun.rehydrate(
                "run-expired", "trace-expired", "thread-1", "agent-1", "work",
                "2020-01-01T00:00:00Z", 8, 1, null, null, 1,
                "2020-01-01T00:00:01Z", "2020-01-01T00:00:00Z", null,
                "2020-01-01T00:00:00Z", AgentRun.Status.RUNNING,
                null, null, null, null
        );
        assertTrue(expired.isTimedOut());
        expired.timeOut("deadline exceeded");
        assertEquals(AgentRun.Status.TIMED_OUT, expired.getStatus());
        assertTrue(expired.isTerminal());
    }

    @Test
    public void authorizationRequiredIsARecoverableRunState() {
        AgentRun run = AgentRun.create("thread-1", "agent-1", "access service");
        run.start();
        run.requireAuthorization("interrupt-auth");

        assertEquals(AgentRun.Status.AUTH_REQUIRED, run.getStatus());
        assertEquals("interrupt-auth", run.getActiveInterruptId());

        run.resume();
        assertEquals(AgentRun.Status.RUNNING, run.getStatus());
    }
}
