package com.ahi.harness.core;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.hooks.HookBus;
import com.ahi.harness.model.ModelClient;
import com.ahi.harness.permission.PermissionDecision;
import com.ahi.harness.permission.PermissionPolicy;
import com.ahi.harness.permission.PermissionPrompter;
import com.ahi.harness.protocol.AgentRun;
import com.ahi.harness.protocol.Artifact;
import com.ahi.harness.protocol.Interrupt;
import com.ahi.harness.protocol.ProtocolError;
import com.ahi.harness.protocol.RunStep;
import com.ahi.harness.runtime.RunRecorder;
import com.ahi.harness.session.CompactionArchiveStore;
import com.ahi.harness.session.ObservationArchiveStore;
import com.ahi.harness.session.SessionStore;
import com.ahi.harness.session.TraceStore;
import com.ahi.harness.tools.Tool;
import com.ahi.harness.tools.ToolRegistry;
import com.ahi.harness.tools.ToolResult;

import java.util.List;

/**
 * Owns the model/tool/observation loop.
 *
 * The loop is deliberately small: ask the model, execute requested tools through
 * permission checks, append observations, and repeat until the model stops
 * asking for tools. RunRecorder mirrors those internal actions into explicit
 * protocol resources without moving persistence or JSON concerns into the loop.
 */
public class AgentLoop {
    private static final int CONTEXT_WARNING_TOKENS = 220000;
    private static final int CONTEXT_NEW_WINDOW_TOKENS = 256000;

    private final ModelClient model;
    private final ToolRegistry tools;
    private final PermissionPolicy permissions;
    private final SessionStore sessionStore;
    private final ConsoleLog log;
    private final HookBus hooks;
    private final PermissionPrompter permissionPrompter;
    private final CompactionArchiveStore compactionArchiveStore;
    private final ObservationArchiveStore observationArchiveStore;
    private final TraceStore traceStore;
    private final RunRecorder runRecorder;
    private final int maxSteps;
    private final int compactMaxMessages;
    private final int compactKeepRecentMessages;
    private final int compactMaxTokens;
    private final int runTimeoutSeconds;

    public AgentLoop(ModelClient model,
                     ToolRegistry tools,
                     PermissionPolicy permissions,
                     SessionStore sessionStore,
                     ConsoleLog log,
                     HookBus hooks,
                     int maxSteps,
                     int compactMaxMessages,
                     int compactKeepRecentMessages) {
        this(model, tools, permissions, sessionStore, log, hooks, null, null, maxSteps, compactMaxMessages, compactKeepRecentMessages);
    }

    public AgentLoop(ModelClient model,
                     ToolRegistry tools,
                     PermissionPolicy permissions,
                     SessionStore sessionStore,
                     ConsoleLog log,
                     HookBus hooks,
                     PermissionPrompter permissionPrompter,
                     CompactionArchiveStore compactionArchiveStore,
                     int maxSteps,
                     int compactMaxMessages,
                     int compactKeepRecentMessages) {
        this(model, tools, permissions, sessionStore, log, hooks, permissionPrompter, compactionArchiveStore, null, null, maxSteps, compactMaxMessages, compactKeepRecentMessages, 0);
    }

    public AgentLoop(ModelClient model,
                     ToolRegistry tools,
                     PermissionPolicy permissions,
                     SessionStore sessionStore,
                     ConsoleLog log,
                     HookBus hooks,
                     PermissionPrompter permissionPrompter,
                     CompactionArchiveStore compactionArchiveStore,
                     ObservationArchiveStore observationArchiveStore,
                     TraceStore traceStore,
                     int maxSteps,
                     int compactMaxMessages,
                     int compactKeepRecentMessages,
                     int compactMaxTokens) {
        this(model, tools, permissions, sessionStore, log, hooks, permissionPrompter,
                compactionArchiveStore, observationArchiveStore, traceStore,
                maxSteps, compactMaxMessages, compactKeepRecentMessages,
                compactMaxTokens, RunRecorder.noop());
    }

    /**
     * Full constructor used by Main and subagents when protocol lifecycle
     * recording is enabled. Older constructors remain available for focused
     * tests and embeddings that do not need a persistent RuntimeStore.
     */
    public AgentLoop(ModelClient model,
                     ToolRegistry tools,
                     PermissionPolicy permissions,
                     SessionStore sessionStore,
                     ConsoleLog log,
                     HookBus hooks,
                     PermissionPrompter permissionPrompter,
                     CompactionArchiveStore compactionArchiveStore,
                     ObservationArchiveStore observationArchiveStore,
                     TraceStore traceStore,
                     int maxSteps,
                     int compactMaxMessages,
                     int compactKeepRecentMessages,
                     int compactMaxTokens,
                     RunRecorder runRecorder) {
        this(model, tools, permissions, sessionStore, log, hooks, permissionPrompter,
                compactionArchiveStore, observationArchiveStore, traceStore,
                maxSteps, compactMaxMessages, compactKeepRecentMessages,
                compactMaxTokens, runRecorder, 0);
    }

    public AgentLoop(ModelClient model,
                     ToolRegistry tools,
                     PermissionPolicy permissions,
                     SessionStore sessionStore,
                     ConsoleLog log,
                     HookBus hooks,
                     PermissionPrompter permissionPrompter,
                     CompactionArchiveStore compactionArchiveStore,
                     ObservationArchiveStore observationArchiveStore,
                     TraceStore traceStore,
                     int maxSteps,
                     int compactMaxMessages,
                     int compactKeepRecentMessages,
                     int compactMaxTokens,
                     RunRecorder runRecorder,
                     int runTimeoutSeconds) {
        this.model = model;
        this.tools = tools;
        this.permissions = permissions;
        this.sessionStore = sessionStore;
        this.log = log;
        this.hooks = hooks;
        this.permissionPrompter = permissionPrompter;
        this.compactionArchiveStore = compactionArchiveStore;
        this.observationArchiveStore = observationArchiveStore;
        this.traceStore = traceStore;
        this.runRecorder = runRecorder == null ? RunRecorder.noop() : runRecorder;
        this.maxSteps = maxSteps;
        this.compactMaxMessages = compactMaxMessages;
        this.compactKeepRecentMessages = compactKeepRecentMessages;
        this.compactMaxTokens = compactMaxTokens;
        this.runTimeoutSeconds = Math.max(0, runTimeoutSeconds);
    }

    public void run(Conversation conversation, String userInput) throws Exception {
        AgentRun run = runRecorder.beginRun(
                userInput,
                maxSteps,
                runTimeoutSeconds,
                conversation.recoveredFromRunId(),
                conversation.recoveredFromCheckpointId()
        );
        conversation.clearRecoveryMarker();
        try {
            trace("user_turn", "run_id=" + run.getId() + ", chars=" + (userInput == null ? 0 : userInput.length()));
            hooks.emit("UserPromptSubmit", userInput);
            log.section("NEW USER TURN / New user turn");
            log.info("RUN", "run_id=" + run.getId() + ", thread_id=" + run.getThreadId());
            log.info("USER", userInput);
            Message userMessage = Message.user(userInput);
            conversation.addMessage(userMessage);
            sessionStore.appendMessage(userMessage);
            runRecorder.message(run, null, userMessage);

            for (int loopIndex = 1; loopIndex <= maxSteps; loopIndex++) {
                if (shouldStopAtSafeBoundary(run)) {
                    return;
                }
                compactIfNeeded(run, conversation);
                ModelResponse response = callModel(run, conversation, loopIndex);

                // A model call may be slow. Check again before accepting its
                // answer as final or starting any requested side effects.
                if (shouldStopAtSafeBoundary(run)) {
                    return;
                }

                if (response.content() != null && !response.content().trim().isEmpty()) {
                    log.info("ASSISTANT", response.content().trim());
                }

                List<ToolCall> calls = response.toolCalls();
                if (calls.isEmpty()) {
                    log.info("HARNESS", "No tool calls returned. Final answer reached.");
                    runRecorder.completeRun(run, response.content() == null ? "" : response.content());
                    hooks.emit("Stop", "final answer");
                    return;
                }

                log.info("HARNESS", "Model requested " + calls.size() + " tool call(s). Execute them and feed observations back.");
                for (ToolCall call : calls) {
                    // Each tool call is a separate safe boundary. A durable
                    // cancellation prevents later calls in the same batch.
                    if (shouldStopAtSafeBoundary(run)) {
                        return;
                    }
                    executeToolCall(run, conversation, call);
                }
            }

            String stop = "Stopped after max loop steps. The harness likely needs a larger max_steps or the model is stuck.";
            Message stopMessage = Message.assistant(stop, null, java.util.Collections.<ToolCall>emptyList());
            conversation.addMessage(stopMessage);
            sessionStore.appendMessage(stopMessage);
            runRecorder.message(run, null, stopMessage);
            runRecorder.checkpoint(run, null, conversation, "max steps reached");
            runRecorder.failRun(run, stop);
            log.error("HARNESS", stop);
            hooks.emit("Stop", "max steps reached");
            trace("stop", "run_id=" + run.getId() + ", max steps reached");
        } catch (Exception error) {
            String detail = errorMessage(error);
            try {
                runRecorder.failRun(run, detail);
            } catch (Exception recordingError) {
                error.addSuppressed(recordingError);
            }
            hooks.emit("Stop", "failed: " + detail);
            trace("stop", "run_id=" + run.getId() + ", failed=" + detail);
            throw error;
        }
    }

    /**
     * Cooperative control-plane boundary. In-flight provider/tool calls are
     * not forcefully interrupted; their next side effect is prevented here.
     */
    private boolean shouldStopAtSafeBoundary(AgentRun run) throws Exception {
        if (run.isTimedOut()) {
            String reason = "Run timed out after " + run.getTimeoutSeconds() + " second(s)";
            runRecorder.timeOutRun(run, reason);
            hooks.emit("Stop", "timed out");
            trace("stop", "run_id=" + run.getId() + ", timed out");
            return true;
        }
        if (runRecorder.cancelIfRequested(run)) {
            hooks.emit("Stop", "cancelled");
            trace("stop", "run_id=" + run.getId() + ", cancelled");
            return true;
        }
        return false;
    }

    /** Keep the top-level loop readable by isolating the compaction lifecycle. */
    private void compactIfNeeded(AgentRun run, Conversation conversation) throws Exception {
        if (!shouldCompact(conversation)) {
            return;
        }
        String detail = "messages=" + conversation.size() + ", estimated_tokens=" + conversation.estimatedTokens();
        RunStep step = null;
        try {
            step = runRecorder.startStep(run, RunStep.Type.COMPACTION, "compact-context", detail, null);
            hooks.emit("PreCompact", detail);
            trace("compact_start", detail);
            CompactionArchiveStore.ArchiveResult archive = compactConversation(conversation);
            String compacted = "messages=" + conversation.size() + ", estimated_tokens=" + conversation.estimatedTokens();
            hooks.emit("PostCompact", compacted);
            trace("compact_end", compacted);
            runRecorder.completeStep(run, step, compacted);
            if (archive != null) {
                runRecorder.artifact(
                        run, step, Artifact.Type.COMPACTION_ARCHIVE,
                        "compacted-messages.jsonl", "application/x-ndjson",
                        archive.path(), null, 0
                );
            }
            runRecorder.checkpoint(run, step, conversation, "context compacted");
        } catch (Exception error) {
            failStepWithoutMasking(run, step, error);
            throw error;
        }
    }

    /** Execute and checkpoint one provider model call as a MODEL_CALL Step. */
    private ModelResponse callModel(AgentRun run,
                                    Conversation conversation,
                                    int loopIndex) throws Exception {
        log.section("AGENT LOOP STEP / Agent loop step " + loopIndex + "/" + maxSteps);
        warnIfContextLarge(conversation);
        log.info("HARNESS", "Prepare model call: messages=" + conversation.messages().size()
                + ", available_tools=" + tools.all().size());

        RunStep step = null;
        try {
            step = runRecorder.startStep(
                    run, RunStep.Type.MODEL_CALL, "model.generate",
                    "messages=" + conversation.messages().size() + ", tools=" + tools.all().size(), null
            );
            hooks.emit("PreModelCall", "messages=" + conversation.messages().size());
            trace("model_call_start", "run_id=" + run.getId() + ", loop=" + loopIndex);
            ModelResponse response = model.generate(conversation, tools);
            trace("model_call_end", "run_id=" + run.getId() + ", tool_calls=" + response.toolCalls().size());
            hooks.emit("PostModelCall", "tool_calls=" + response.toolCalls().size());
            Message assistantMessage = Message.assistant(response.content(), response.reasoningContent(), response.toolCalls());
            conversation.addMessage(assistantMessage);
            sessionStore.appendMessage(assistantMessage);
            runRecorder.message(run, step, assistantMessage);
            runRecorder.completeStep(
                    run, step,
                    "content_chars=" + length(response.content()) + ", tool_calls=" + response.toolCalls().size()
            );
            runRecorder.checkpoint(run, step, conversation, "model response appended");
            return response;
        } catch (Exception error) {
            failStepWithoutMasking(run, step, error);
            throw error;
        }
    }

    /**
     * Execute one ToolCall including Guardrail, optional Interrupt, Tool Step,
     * observation Artifact and Checkpoint. Tool exceptions become data so the
     * next model call can choose how to recover.
     */
    private void executeToolCall(AgentRun run,
                                 Conversation conversation,
                                 ToolCall call) throws Exception {
        String hookDetail = call.name() + " | " + (call.argumentsJson() == null ? "" : call.argumentsJson());
        RunStep activeStep = null;
        try {
            RunStep guardrailStep = runRecorder.startStep(
                    run, RunStep.Type.GUARDRAIL, "authorize:" + call.name(),
                    trim(call.argumentsJson(), 1000), call.id()
            );
            activeStep = guardrailStep;

            if (!hooks.emit("PreToolUse", hookDetail)) {
                String reason = "Hook blocked tool call: " + call.name();
                runRecorder.permission(run, guardrailStep, "DENY | " + reason);
                runRecorder.blockStep(run, guardrailStep, reason);
                activeStep = null;
                appendToolObservation(
                        run, guardrailStep, conversation, call,
                        ToolResult.failure(reason), "blocked tool observation appended"
                );
                hooks.emit("PostToolUse", hookDetail);
                return;
            }

            log.section("TOOL CALL / Tool call: " + call.name());
            log.block("TOOL", "Arguments", call.argumentsJson());
            PermissionDecision decision = permissions.check(call);
            if (decision.requiresApproval()) {
                Interrupt interrupt = runRecorder.openInterrupt(
                        run, guardrailStep, Interrupt.Type.TOOL_APPROVAL,
                        "Approve tool call " + call.name(), call.argumentsJson()
                );
                runRecorder.checkpoint(run, guardrailStep, conversation, "waiting for tool approval");
                decision = permissionPrompter == null
                        ? PermissionDecision.deny("approval required but no permission prompter is available: " + decision.reason())
                        : permissionPrompter.resolve(call, decision);
                runRecorder.resolveInterrupt(run, interrupt, decision.allowed(), decision.reason());
            }

            String permissionDetail = (decision.allowed() ? "ALLOW" : "DENY") + " | " + decision.reason();
            runRecorder.permission(run, guardrailStep, permissionDetail);
            log.info("PERMISSION", decision.allowed()
                    ? "allowed: " + decision.reason()
                    : "denied: " + decision.reason());
            trace("permission", "run_id=" + run.getId() + ", " + call.name() + ": " + permissionDetail);
            if (decision.allowed()) {
                runRecorder.completeStep(run, guardrailStep, permissionDetail);
            } else {
                runRecorder.blockStep(run, guardrailStep, permissionDetail);
            }
            activeStep = null;

            ToolResult result;
            RunStep toolStep = null;
            if (decision.allowed()) {
                RunStep.Type type = "subagent_run".equals(call.name())
                        ? RunStep.Type.SUBAGENT_TASK : RunStep.Type.TOOL_CALL;
                toolStep = runRecorder.startStep(
                        run, type, call.name(), trim(call.argumentsJson(), 2000), call.id()
                );
                activeStep = toolStep;
                result = executeToolAsData(run, call);
                if (result.success()) {
                    runRecorder.completeStep(run, toolStep, trim(result.content(), 1000));
                } else {
                    runRecorder.failStep(
                            run, toolStep, trim(result.content(), 1000),
                            ProtocolError.Source.TOOL, true
                    );
                }
                activeStep = null;
            } else {
                result = ToolResult.failure("Permission denied: " + decision.reason());
            }

            RunStep producingStep = toolStep == null ? guardrailStep : toolStep;
            appendToolObservation(
                    run, producingStep, conversation, call, result,
                    "tool observation appended"
            );
            hooks.emit("PostToolUse", hookDetail);
        } catch (Exception error) {
            failStepWithoutMasking(run, activeStep, error);
            throw error;
        }
    }

    private ToolResult executeToolAsData(AgentRun run, ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.failure("Unknown tool: " + call.name());
        }
        trace("tool_call_start", "run_id=" + run.getId() + ", tool=" + call.name());
        try {
            return tool.execute(call.arguments());
        } catch (Exception toolError) {
            return ToolResult.failure("Tool execution exception: " + errorMessage(toolError));
        } finally {
            trace("tool_call_end", "run_id=" + run.getId() + ", tool=" + call.name());
        }
    }

    private void appendToolObservation(AgentRun run,
                                       RunStep producingStep,
                                       Conversation conversation,
                                       ToolCall call,
                                       ToolResult result,
                                       String checkpointReason) throws Exception {
        String observation = prepareObservation(run, producingStep, call.name(), result.toObservation());
        log.block("OBSERVATION", "Tool result that will be sent back to the model", trimForConsole(observation));
        Message toolMessage = Message.tool(call.id(), observation);
        conversation.addMessage(toolMessage);
        sessionStore.appendMessage(toolMessage);
        runRecorder.message(run, producingStep, toolMessage);
        runRecorder.checkpoint(run, producingStep, conversation, checkpointReason);
    }

    private void failStepWithoutMasking(AgentRun run, RunStep step, Exception original) {
        try {
            runRecorder.failStep(run, step, errorMessage(original));
        } catch (Exception recordingError) {
            original.addSuppressed(recordingError);
        }
    }

    private static String trimForConsole(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n");
        if (normalized.length() <= 900) {
            return normalized;
        }
        return normalized.substring(0, 900) + "\n...<truncated " + (normalized.length() - 900) + " chars>";
    }

    private void warnIfContextLarge(Conversation conversation) {
        int estimatedTokens = conversation.estimatedTokens();
        if (estimatedTokens >= CONTEXT_NEW_WINDOW_TOKENS) {
            log.error("CONTEXT", "Estimated context is " + estimatedTokens
                    + " tokens, above 256K. Please start a new window/thread and carry over only the key summary.");
        } else if (estimatedTokens >= CONTEXT_WARNING_TOKENS) {
            log.info("CONTEXT", "Estimated context is " + estimatedTokens
                    + " tokens and approaching 256K. Consider compacting or opening a new window soon.");
        }
    }

    private boolean shouldCompact(Conversation conversation) {
        // Message count is easy to reason about; token estimate catches large
        // tool outputs that would otherwise hide inside a small number of turns.
        boolean byMessages = compactMaxMessages > 0 && conversation.size() > compactMaxMessages;
        boolean byTokens = compactMaxTokens > 0 && conversation.estimatedTokens() > compactMaxTokens;
        return byMessages || byTokens;
    }

    private CompactionArchiveStore.ArchiveResult compactConversation(Conversation conversation) throws Exception {
        if (compactionArchiveStore == null) {
            conversation.compact(compactKeepRecentMessages);
            return null;
        }
        java.util.List<Message> archivedPreview = previewArchivedMessages(conversation);
        if (archivedPreview.isEmpty()) {
            return null;
        }
        CompactionArchiveStore.ArchiveResult archive = compactionArchiveStore.archive(archivedPreview);
        conversation.compact(compactKeepRecentMessages, archive.path());
        log.info("CONTEXT", "Archived " + archive.messageCount() + " compacted message(s) to " + archive.path());
        return archive;
    }

    private String prepareObservation(AgentRun run,
                                      RunStep step,
                                      String toolName,
                                      String observation) throws Exception {
        // The model gets a bounded preview; oversized raw output stays recoverable
        // through .harness/observations and read_file/grep. When archival occurs,
        // the same file also becomes a first-class Artifact linked to its Step.
        ObservationArchiveStore.ArchiveResult archived = observationArchiveStore == null
                ? new ObservationArchiveStore.ArchiveResult(
                        observation == null ? "" : observation,
                        false,
                        null,
                        observation == null ? 0 : observation.length()
                )
                : observationArchiveStore.archive(toolName, observation);
        String prepared = archived.visibleContent();
        if (archived.archived()) {
            runRecorder.artifact(
                    run,
                    step,
                    Artifact.Type.TOOL_OBSERVATION,
                    toolName + "-observation.txt",
                    "text/plain; charset=utf-8",
                    archived.archivePath(),
                    null,
                    archived.originalChars()
            );
        }
        trace("observation", toolName + ", chars=" + (prepared == null ? 0 : prepared.length()));
        return prepared;
    }

    private static String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n");
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...<truncated " + (normalized.length() - maxChars) + " chars>";
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    private void trace(String event, String detail) {
        if (traceStore != null) {
            traceStore.record(event, detail);
        }
    }

    private java.util.List<Message> previewArchivedMessages(Conversation conversation) {
        // Keep system messages and avoid starting the retained suffix with a tool
        // result whose paired assistant tool call was archived.
        java.util.List<Message> messages = conversation.messages();
        java.util.List<Message> archived = new java.util.ArrayList<Message>();
        int firstNonSystem = 0;
        while (firstNonSystem < messages.size() && "system".equals(messages.get(firstNonSystem).role())) {
            firstNonSystem++;
        }
        int keepStart = Math.max(firstNonSystem, messages.size() - compactKeepRecentMessages);
        while (keepStart > firstNonSystem && "tool".equals(messages.get(keepStart).role())) {
            keepStart--;
        }
        if (keepStart > firstNonSystem) {
            archived.addAll(messages.subList(firstNonSystem, keepStart));
        }
        return archived;
    }
}
