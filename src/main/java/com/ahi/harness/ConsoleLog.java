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
            return "HARNESS/运行时";
        }
        if ("USER".equals(stage)) {
            return "USER/用户";
        }
        if ("ASSISTANT".equals(stage)) {
            return "ASSISTANT/助手";
        }
        if ("MODEL".equals(stage)) {
            return "MODEL/模型";
        }
        if ("MODEL INPUT".equals(stage)) {
            return "MODEL INPUT/模型输入";
        }
        if ("MODEL OUTPUT".equals(stage)) {
            return "MODEL OUTPUT/模型输出";
        }
        if ("MODEL HTTP".equals(stage)) {
            return "MODEL HTTP/模型HTTP";
        }
        if ("MODEL REQUEST JSON".equals(stage)) {
            return "MODEL REQUEST JSON/模型请求体";
        }
        if ("MODEL RESPONSE JSON".equals(stage)) {
            return "MODEL RESPONSE JSON/模型响应体";
        }
        if ("TOOL".equals(stage)) {
            return "TOOL/工具";
        }
        if ("PERMISSION".equals(stage)) {
            return "PERMISSION/权限";
        }
        if ("OBSERVATION".equals(stage)) {
            return "OBSERVATION/观察结果";
        }
        if ("DIFF".equals(stage)) {
            return "DIFF/变更预览";
        }
        return stage + "/阶段";
    }
}
