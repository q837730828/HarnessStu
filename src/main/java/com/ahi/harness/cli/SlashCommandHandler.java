package com.ahi.harness.cli;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.core.Conversation;
import com.ahi.harness.core.Message;
import com.ahi.harness.session.JsonlSessionStore;
import com.ahi.harness.tools.Tool;
import com.ahi.harness.tools.ToolRegistry;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SlashCommandHandler {
    private final File workspace;
    private final File sessionDirectory;
    private final ToolRegistry tools;
    private final ConsoleLog log;
    private final int compactKeepRecentMessages;

    public SlashCommandHandler(File workspace,
                               File sessionDirectory,
                               ToolRegistry tools,
                               ConsoleLog log,
                               int compactKeepRecentMessages) {
        this.workspace = workspace;
        this.sessionDirectory = sessionDirectory;
        this.tools = tools;
        this.log = log;
        this.compactKeepRecentMessages = compactKeepRecentMessages;
    }

    public boolean handle(String line, Conversation conversation) throws Exception {
        String trimmed = line.trim();
        if (!trimmed.startsWith("/")) {
            return false;
        }
        if ("/help".equals(trimmed)) {
            log.block("HARNESS", "Slash commands", "/help\n/tools\n/status\n/diff\n/sessions\n/resume latest\n/resume <session-file>\n/compact\n/exit");
            return true;
        }
        if ("/tools".equals(trimmed)) {
            StringBuilder out = new StringBuilder();
            for (Tool tool : tools.all()) {
                out.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
            }
            log.block("HARNESS", "Available tools", out.toString());
            return true;
        }
        if ("/status".equals(trimmed)) {
            log.info("HARNESS", "workspace=" + workspace.getAbsolutePath()
                    + ", messages=" + conversation.size()
                    + ", sessions=" + JsonlSessionStore.listSessions(sessionDirectory).size());
            return true;
        }
        if ("/diff".equals(trimmed)) {
            log.block("HARNESS", "git diff", runGitDiff());
            return true;
        }
        if ("/sessions".equals(trimmed)) {
            List<File> sessions = JsonlSessionStore.listSessions(sessionDirectory);
            StringBuilder out = new StringBuilder();
            for (File session : sessions) {
                out.append(session.getName()).append('\n');
            }
            log.block("HARNESS", "Sessions", out.length() == 0 ? "No sessions found." : out.toString());
            return true;
        }
        if (trimmed.startsWith("/resume")) {
            File session = resolveSession(trimmed);
            if (session == null || !session.exists()) {
                log.error("HARNESS", "Session not found. Use /sessions first.");
                return true;
            }
            List<Message> loaded = JsonlSessionStore.loadMessages(session);
            if (loaded.isEmpty()) {
                log.error("HARNESS", "No resumable messages in " + session.getName());
                return true;
            }
            List<Message> merged = new ArrayList<Message>();
            for (Message message : conversation.messages()) {
                if ("system".equals(message.role())) {
                    merged.add(message);
                }
            }
            for (Message message : loaded) {
                if (!"system".equals(message.role())) {
                    merged.add(message);
                }
            }
            conversation.replaceMessages(merged);
            log.info("HARNESS", "Resumed " + loaded.size() + " message(s) from " + session.getName());
            return true;
        }
        if ("/compact".equals(trimmed)) {
            int before = conversation.size();
            conversation.compact(compactKeepRecentMessages);
            log.info("HARNESS", "Compacted conversation: " + before + " -> " + conversation.size() + " messages");
            return true;
        }
        return false;
    }

    private File resolveSession(String command) {
        String[] parts = command.split("\\s+", 2);
        if (parts.length == 1 || "latest".equalsIgnoreCase(parts[1].trim())) {
            return JsonlSessionStore.latestSession(sessionDirectory);
        }
        return new File(sessionDirectory, parts[1].trim());
    }

    private String runGitDiff() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("git", "diff");
        builder.directory(workspace);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (out.length() < 12000) {
                out.append(line).append('\n');
            }
        }
        int exit = process.waitFor();
        if (out.length() == 0) {
            out.append("No diff. exit_code=").append(exit);
        }
        return out.toString();
    }
}
