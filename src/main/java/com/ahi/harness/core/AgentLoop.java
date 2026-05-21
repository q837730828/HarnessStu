package com.ahi.harness.core;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.hooks.HookBus;
import com.ahi.harness.model.ModelClient;
import com.ahi.harness.permission.PermissionDecision;
import com.ahi.harness.permission.PermissionPolicy;
import com.ahi.harness.permission.PermissionPrompter;
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
 * asking for tools.
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
    private final int maxSteps;
    private final int compactMaxMessages;
    private final int compactKeepRecentMessages;
    private final int compactMaxTokens;

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
        this.maxSteps = maxSteps;
        this.compactMaxMessages = compactMaxMessages;
        this.compactKeepRecentMessages = compactKeepRecentMessages;
        this.compactMaxTokens = compactMaxTokens;
    }

    public void run(Conversation conversation, String userInput) throws Exception {
        trace("user_turn", "chars=" + (userInput == null ? 0 : userInput.length()));
        hooks.emit("UserPromptSubmit", userInput);
        log.section("NEW USER TURN / New user turn");
        log.info("USER", userInput);
        conversation.addUser(userInput);
        sessionStore.appendMessage(Message.user(userInput));

        for (int step = 1; step <= maxSteps; step++) {
            // Compact before the next model call so the request never grows past
            // the configured active-context budget.
            if (shouldCompact(conversation)) {
                String detail = "messages=" + conversation.size() + ", estimated_tokens=" + conversation.estimatedTokens();
                hooks.emit("PreCompact", detail);
                trace("compact_start", detail);
                compactConversation(conversation);
                hooks.emit("PostCompact", "messages=" + conversation.size() + ", estimated_tokens=" + conversation.estimatedTokens());
                trace("compact_end", "messages=" + conversation.size() + ", estimated_tokens=" + conversation.estimatedTokens());
            }

            log.section("AGENT LOOP STEP / Agent loop step " + step + "/" + maxSteps);
            warnIfContextLarge(conversation);
            log.info("HARNESS", "Prepare model call: messages=" + conversation.messages().size()
                    + ", available_tools=" + tools.all().size());

            hooks.emit("PreModelCall", "messages=" + conversation.messages().size());
            trace("model_call_start", "step=" + step + ", messages=" + conversation.messages().size() + ", tools=" + tools.all().size());
            ModelResponse response = model.generate(conversation, tools);
            trace("model_call_end", "step=" + step + ", tool_calls=" + response.toolCalls().size());
            hooks.emit("PostModelCall", "tool_calls=" + response.toolCalls().size());
            conversation.addAssistant(response.content(), response.reasoningContent(), response.toolCalls());
            sessionStore.appendMessage(Message.assistant(response.content(), response.reasoningContent(), response.toolCalls()));

            if (response.content() != null && !response.content().trim().isEmpty()) {
                log.info("ASSISTANT", response.content().trim());
            }

            List<ToolCall> calls = response.toolCalls();
            if (calls.isEmpty()) {
                log.info("HARNESS", "No tool calls returned. Final answer reached.");
                hooks.emit("Stop", "final answer");
                return;
            }

            log.info("HARNESS", "Model requested " + calls.size() + " tool call(s). Execute them and feed observations back.");
            for (ToolCall call : calls) {
                String hookDetail = call.name() + " | " + (call.argumentsJson() == null ? "" : call.argumentsJson());
                // Blocking hooks can veto a call before permissions and tool code
                // run. The veto is still returned as an observation to the model.
                if (!hooks.emit("PreToolUse", hookDetail)) {
                    ToolResult blocked = ToolResult.failure("Hook blocked tool call: " + call.name());
                    String observation = prepareObservation(call.name(), blocked.toObservation());
                    log.block("OBSERVATION", "Tool result that will be sent back to the model", observation);
                    conversation.addToolResult(call.id(), observation);
                    sessionStore.appendMessage(Message.tool(call.id(), observation));
                    hooks.emit("PostToolUse", hookDetail);
                    continue;
                }
                log.section("TOOL CALL / Tool call: " + call.name());
                log.block("TOOL", "Arguments", call.argumentsJson());
                PermissionDecision decision = permissions.check(call);
                // ASK decisions are resolved at runtime; in non-interactive flows
                // they become denials unless a prompter has been wired in.
                if (decision.requiresApproval()) {
                    decision = permissionPrompter == null
                            ? PermissionDecision.deny("approval required but no permission prompter is available: " + decision.reason())
                            : permissionPrompter.resolve(call, decision);
                }
                log.info("PERMISSION", decision.allowed()
                        ? "allowed: " + decision.reason()
                        : "denied: " + decision.reason());
                trace("permission", call.name() + ": " + (decision.allowed() ? "allowed" : "denied") + " | " + decision.reason());

                ToolResult result;
                if (decision.allowed()) {
                    Tool tool = tools.get(call.name());
                    if (tool == null) {
                        result = ToolResult.failure("Unknown tool: " + call.name());
                    } else {
                        trace("tool_call_start", call.name());
                        result = tool.execute(call.arguments());
                        trace("tool_call_end", call.name());
                    }
                } else {
                    result = ToolResult.failure("Permission denied: " + decision.reason());
                }

                String observation = prepareObservation(call.name(), result.toObservation());
                log.block("OBSERVATION", "Tool result that will be sent back to the model", trimForConsole(observation));
                conversation.addToolResult(call.id(), observation);
                sessionStore.appendMessage(Message.tool(call.id(), observation));
                hooks.emit("PostToolUse", hookDetail);
            }
        }

        String stop = "Stopped after max loop steps. The harness likely needs a larger max_steps or the model is stuck.";
        conversation.addAssistant(stop, java.util.Collections.<ToolCall>emptyList());
        log.error("HARNESS", stop);
        hooks.emit("Stop", "max steps reached");
        trace("stop", "max steps reached");
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

    private void compactConversation(Conversation conversation) throws Exception {
        if (compactionArchiveStore == null) {
            conversation.compact(compactKeepRecentMessages);
            return;
        }
        java.util.List<Message> archivedPreview = previewArchivedMessages(conversation);
        if (archivedPreview.isEmpty()) {
            return;
        }
        CompactionArchiveStore.ArchiveResult archive = compactionArchiveStore.archive(archivedPreview);
        conversation.compact(compactKeepRecentMessages, archive.path());
        log.info("CONTEXT", "Archived " + archive.messageCount() + " compacted message(s) to " + archive.path());
    }

    private String prepareObservation(String toolName, String observation) throws Exception {
        // The model gets a bounded preview; oversized raw output stays recoverable
        // through .harness/observations and read_file/grep.
        String prepared = observationArchiveStore == null
                ? observation
                : observationArchiveStore.archiveIfLarge(toolName, observation);
        trace("observation", toolName + ", chars=" + (prepared == null ? 0 : prepared.length()));
        return prepared;
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
