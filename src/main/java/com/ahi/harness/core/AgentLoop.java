package com.ahi.harness.core;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.hooks.HookBus;
import com.ahi.harness.model.ModelClient;
import com.ahi.harness.permission.PermissionDecision;
import com.ahi.harness.permission.PermissionPolicy;
import com.ahi.harness.session.SessionStore;
import com.ahi.harness.tools.Tool;
import com.ahi.harness.tools.ToolRegistry;
import com.ahi.harness.tools.ToolResult;

import java.util.List;

public class AgentLoop {
    private final ModelClient model;
    private final ToolRegistry tools;
    private final PermissionPolicy permissions;
    private final SessionStore sessionStore;
    private final ConsoleLog log;
    private final HookBus hooks;
    private final int maxSteps;
    private final int compactMaxMessages;
    private final int compactKeepRecentMessages;

    public AgentLoop(ModelClient model,
                     ToolRegistry tools,
                     PermissionPolicy permissions,
                     SessionStore sessionStore,
                     ConsoleLog log,
                     HookBus hooks,
                     int maxSteps,
                     int compactMaxMessages,
                     int compactKeepRecentMessages) {
        this.model = model;
        this.tools = tools;
        this.permissions = permissions;
        this.sessionStore = sessionStore;
        this.log = log;
        this.hooks = hooks;
        this.maxSteps = maxSteps;
        this.compactMaxMessages = compactMaxMessages;
        this.compactKeepRecentMessages = compactKeepRecentMessages;
    }

    public void run(Conversation conversation, String userInput) throws Exception {
        hooks.emit("UserPromptSubmit", userInput);
        log.section("NEW USER TURN / New user turn");
        log.info("USER", userInput);
        conversation.addUser(userInput);
        sessionStore.appendMessage(Message.user(userInput));

        for (int step = 1; step <= maxSteps; step++) {
            if (conversation.size() > compactMaxMessages) {
                hooks.emit("PreCompact", "messages=" + conversation.size());
                conversation.compact(compactKeepRecentMessages);
                hooks.emit("PostCompact", "messages=" + conversation.size());
            }

            log.section("AGENT LOOP STEP / Agent loop step " + step + "/" + maxSteps);
            log.info("HARNESS", "Prepare model call: messages=" + conversation.messages().size()
                    + ", available_tools=" + tools.all().size());

            hooks.emit("PreModelCall", "messages=" + conversation.messages().size());
            ModelResponse response = model.generate(conversation, tools);
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
                hooks.emit("PreToolUse", call.name());
                log.section("TOOL CALL / Tool call: " + call.name());
                log.block("TOOL", "Arguments", call.argumentsJson());
                PermissionDecision decision = permissions.check(call);
                log.info("PERMISSION", decision.allowed()
                        ? "allowed: " + decision.reason()
                        : "denied: " + decision.reason());

                ToolResult result;
                if (decision.allowed()) {
                    Tool tool = tools.get(call.name());
                    if (tool == null) {
                        result = ToolResult.failure("Unknown tool: " + call.name());
                    } else {
                        result = tool.execute(call.arguments());
                    }
                } else {
                    result = ToolResult.failure("Permission denied: " + decision.reason());
                }

                String observation = result.toObservation();
                log.block("OBSERVATION", "Tool result that will be sent back to the model", trimForConsole(observation));
                conversation.addToolResult(call.id(), observation);
                sessionStore.appendMessage(Message.tool(call.id(), observation));
                hooks.emit("PostToolUse", call.name());
            }
        }

        String stop = "Stopped after max loop steps. The harness likely needs a larger max_steps or the model is stuck.";
        conversation.addAssistant(stop, java.util.Collections.<ToolCall>emptyList());
        log.error("HARNESS", stop);
        hooks.emit("Stop", "max steps reached");
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
}
