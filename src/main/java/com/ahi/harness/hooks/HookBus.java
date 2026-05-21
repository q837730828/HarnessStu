package com.ahi.harness.hooks;

import com.ahi.harness.ConsoleLog;
import com.ahi.harness.config.HarnessSettings;
import com.ahi.harness.process.Utf8Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Emits lifecycle events to optional external commands.
 *
 * Hooks are deliberately outside the model/tool abstractions: they are runtime
 * guardrails that can observe or block actions such as PreToolUse.
 */
public class HookBus {
    private final ConsoleLog log;
    private final File workspace;
    private final List<HarnessSettings.HookCommand> hooks;

    public HookBus(ConsoleLog log) {
        this(null, log, Collections.<HarnessSettings.HookCommand>emptyList());
    }

    public HookBus(File workspace, ConsoleLog log, List<HarnessSettings.HookCommand> hooks) {
        this.workspace = workspace;
        this.log = log;
        this.hooks = hooks == null ? Collections.<HarnessSettings.HookCommand>emptyList() : hooks;
    }

    public boolean emit(String event, String detail) {
        log.info("HOOK", event + (detail == null || detail.trim().isEmpty() ? "" : " | " + detail));
        boolean allowed = true;
        for (HarnessSettings.HookCommand hook : hooks) {
            if (!event.equals(hook.event())) {
                continue;
            }
            if (!matchesTool(hook, event, detail)) {
                continue;
            }
            boolean ok = runHook(hook, event, detail);
            if (hook.blocking() && !ok) {
                // Nonblocking hooks are telemetry; blocking hooks become a policy
                // gate for the caller.
                allowed = false;
            }
        }
        return allowed;
    }

    private boolean runHook(HarnessSettings.HookCommand hook, String event, String detail) {
        try {
            List<String> command = hook.script() == null || hook.script().trim().isEmpty()
                    ? inlineCommand(hook.command())
                    : scriptCommand(hook);
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workspace != null) {
                builder.directory(workspace);
            }
            Utf8Process.apply(builder);
            builder.redirectErrorStream(true);
            builder.environment().put("HARNESS_HOOK_EVENT", event);
            builder.environment().put("HARNESS_HOOK_DETAIL", detail == null ? "" : detail);
            Process process = builder.start();
            boolean finished = process.waitFor(hook.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("HOOK", "hook timed out: event=" + event + ", target=" + hookTarget(hook));
                return false;
            }
            String output = readOutput(process);
            int exit = process.exitValue();
            log.info("HOOK", "hook target exit=" + exit + ", blocking=" + hook.blocking() + ", target=" + hookTarget(hook));
            if (!output.trim().isEmpty()) {
                log.block("HOOK", "Hook output", trim(output, 4000));
            }
            return exit == 0;
        } catch (Exception e) {
            log.error("HOOK", "hook failed: " + e.getMessage());
            return false;
        }
    }

    private boolean matchesTool(HarnessSettings.HookCommand hook, String event, String detail) {
        if (!"PreToolUse".equals(event) && !"PostToolUse".equals(event)) {
            return true;
        }
        if (hook.tools().isEmpty()) {
            return true;
        }
        if (detail == null) {
            return false;
        }
        for (String tool : hook.tools()) {
            if (detail.equals(tool) || detail.startsWith(tool + " | ")) {
                return true;
            }
        }
        return false;
    }

    private List<String> inlineCommand(String commandText) {
        // Inline hooks run through PowerShell because this project targets a
        // Windows-first learning environment.
        List<String> command = new ArrayList<String>();
        command.add("powershell");
        command.add("-NoProfile");
        command.add("-Command");
        command.add("chcp 65001 > $null; "
                + "[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false); "
                + "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); "
                + "$OutputEncoding=[System.Text.UTF8Encoding]::new($false); "
                + commandText);
        return command;
    }

    private List<String> scriptCommand(HarnessSettings.HookCommand hook) throws Exception {
        File script = resolveScript(hook.script());
        String lower = script.getName().toLowerCase();
        List<String> command = new ArrayList<String>();
        if (lower.endsWith(".ps1")) {
            command.add("powershell");
            command.add("-NoProfile");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-Command");
            command.add("chcp 65001 > $null; "
                    + "[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false); "
                    + "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); "
                    + "$OutputEncoding=[System.Text.UTF8Encoding]::new($false); "
                    + "& '" + escapePowerShell(script.getAbsolutePath()) + "'" + powerShellArgs(hook.args()));
            return command;
        } else if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            command.add("cmd");
            command.add("/c");
            command.add("chcp 65001 > nul && \"" + script.getAbsolutePath() + "\"" + cmdArgs(hook.args()));
            return command;
        } else if (lower.endsWith(".py")) {
            command.add("python");
            command.add("-X");
            command.add("utf8");
            command.add(script.getAbsolutePath());
        } else {
            throw new IllegalArgumentException("unsupported hook script extension: " + script.getName());
        }
        command.addAll(hook.args());
        return command;
    }

    private String escapePowerShell(String value) {
        return value.replace("'", "''");
    }

    private String powerShellArgs(List<String> args) {
        StringBuilder out = new StringBuilder();
        for (String arg : args) {
            out.append(" '").append(escapePowerShell(arg)).append("'");
        }
        return out.toString();
    }

    private String cmdArgs(List<String> args) {
        StringBuilder out = new StringBuilder();
        for (String arg : args) {
            out.append(" \"").append(arg.replace("\"", "\"\"")).append("\"");
        }
        return out.toString();
    }

    private File resolveScript(String path) throws Exception {
        if (workspace == null) {
            throw new IllegalStateException("workspace is required for hook script execution");
        }
        File script = new File(path);
        if (!script.isAbsolute()) {
            script = new File(workspace, path);
        }
        File root = workspace.getCanonicalFile();
        File target = script.getCanonicalFile();
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            // Hook scripts are code execution, so they must stay inside the
            // current workspace rather than arbitrary absolute paths.
            throw new IllegalArgumentException("hook script escapes workspace: " + path);
        }
        if (!target.exists() || !target.isFile()) {
            throw new IllegalArgumentException("hook script not found: " + path);
        }
        return target;
    }

    private String hookTarget(HarnessSettings.HookCommand hook) {
        return hook.script() == null || hook.script().trim().isEmpty() ? hook.command() : hook.script();
    }

    private String readOutput(Process process) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private String trim(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "\n...<truncated " + (value.length() - maxChars) + " chars>";
    }
}
