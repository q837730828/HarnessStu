package com.ahi.harness;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class ConsoleLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public void info(String stage, String message) {
        print(stage, message);
    }

    public void error(String stage, String message) {
        print(stage, "ERROR: " + message);
    }

    public void section(String title) {
        System.out.println();
        System.out.println("========== " + title + " ==========");
    }

    public void block(String stage, String title, String body) {
        print(stage, title);
        if (body == null || body.isEmpty()) {
            return;
        }
        String[] lines = body.replace("\r\n", "\n").split("\n", -1);
        for (String line : lines) {
            System.out.println("    " + line);
        }
    }

    public void divider() {
        System.out.println();
    }

    private void print(String stage, String message) {
        System.out.println("[" + TIME.format(LocalTime.now()) + "][" + stageLabel(stage) + "] " + message);
    }

    private String stageLabel(String stage) {
        if ("HARNESS".equals(stage)) {
            return "HARNESS/\u8fd0\u884c\u65f6";
        }
        if ("USER".equals(stage)) {
            return "USER/\u7528\u6237";
        }
        if ("ASSISTANT".equals(stage)) {
            return "ASSISTANT/\u52a9\u624b";
        }
        if ("MODEL".equals(stage)) {
            return "MODEL/\u6a21\u578b";
        }
        if ("MODEL HTTP".equals(stage)) {
            return "MODEL HTTP/\u6a21\u578bHTTP";
        }
        if ("MODEL REQUEST JSON".equals(stage)) {
            return "MODEL REQUEST JSON/\u6a21\u578b\u8bf7\u6c42\u4f53";
        }
        if ("MODEL RESPONSE JSON".equals(stage)) {
            return "MODEL RESPONSE JSON/\u6a21\u578b\u54cd\u5e94\u4f53";
        }
        if ("TOOL".equals(stage)) {
            return "TOOL/\u5de5\u5177";
        }
        if ("PERMISSION".equals(stage)) {
            return "PERMISSION/\u6743\u9650";
        }
        if ("OBSERVATION".equals(stage)) {
            return "OBSERVATION/\u89c2\u5bdf\u7ed3\u679c";
        }
        if ("DIFF".equals(stage)) {
            return "DIFF/\u53d8\u66f4\u9884\u89c8";
        }
        if ("VALIDATION".equals(stage)) {
            return "VALIDATION/\u9a8c\u8bc1";
        }
        if ("HOOK".equals(stage)) {
            return "HOOK/\u94a9\u5b50";
        }
        if ("EXTERNAL".equals(stage)) {
            return "EXTERNAL/\u5916\u90e8\u5de5\u5177";
        }
        if ("MCP HTTP REQUEST".equals(stage)) {
            return "MCP HTTP REQUEST/HTTP\u8bf7\u6c42\u4f53";
        }
        if ("MCP HTTP RESPONSE".equals(stage)) {
            return "MCP HTTP RESPONSE/HTTP\u54cd\u5e94\u4f53";
        }
        if ("MCP STDIO REQUEST".equals(stage)) {
            return "MCP STDIO REQUEST/STDIO\u8bf7\u6c42\u4f53";
        }
        if ("MCP STDIO RESPONSE".equals(stage)) {
            return "MCP STDIO RESPONSE/STDIO\u54cd\u5e94\u4f53";
        }
        return stage + "/\u9636\u6bb5";
    }
}
