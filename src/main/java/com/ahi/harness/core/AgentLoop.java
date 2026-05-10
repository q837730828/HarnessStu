package com.ahi.harness.core;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.model.ModelClient;
import com.ahi.harness.permission.PermissionDecision;
import com.ahi.harness.permission.PermissionPolicy;
import com.ahi.harness.session.SessionStore;
import com.ahi.harness.tools.Tool;
import com.ahi.harness.tools.ToolRegistry;
import com.ahi.harness.tools.ToolResult;

import java.util.List;

public class AgentLoop {
    private static final int MAX_STEPS = 48;

    private final ModelClient model;
    private final ToolRegistry tools;
    private final PermissionPolicy permissions;
    private final SessionStore sessionStore;
    private final ConsoleLog log;

    public AgentLoop(ModelClient model,
                     ToolRegistry tools,
                     PermissionPolicy permissions,
                     SessionStore sessionStore,
                     ConsoleLog log) {
        this.model = model;
        this.tools = tools;
        this.permissions = permissions;
        this.sessionStore = sessionStore;
        this.log = log;
    }

    public void run(Conversation conversation, String userInput) throws Exception {
        log.section("NEW USER TURN / 新用户回合");
        log.info("USER", userInput);
        conversation.addUser(userInput);
        sessionStore.append("user", userInput);

        for (int step = 1; step <= MAX_STEPS; step++) {
            log.section("AGENT LOOP STEP / 代理循环步骤 " + step + "/" + MAX_STEPS);
            log.info("HARNESS", "Prepare model call: messages=" + conversation.messages().size()
                    + ", available_tools=" + tools.all().size());

            ModelResponse response = model.generate(conversation, tools);
            conversation.addAssistant(response.content(), response.reasoningContent(), response.toolCalls());
            sessionStore.append("assistant", response.summaryForLog());

            if (response.content() != null && !response.content().trim().isEmpty()) {
                log.info("ASSISTANT", response.content().trim());
            }

            List<ToolCall> calls = response.toolCalls();
            if (calls.isEmpty()) {
                log.info("HARNESS", "No tool calls returned. Final answer reached.");
                return;
            }

            log.info("HARNESS", "Model requested " + calls.size() + " tool call(s). Execute them and feed observations back.");
            for (ToolCall call : calls) {
                log.section("TOOL CALL / 工具调用: " + call.name());
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
                sessionStore.append("tool:" + call.name(), observation);
            }
        }

        String stop = "Stopped after max loop steps. The harness likely needs a larger MAX_STEPS or the model is stuck.";
        conversation.addAssistant(stop, java.util.Collections.<ToolCall>emptyList());
        log.error("HARNESS", stop);
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
