package com.ahi.harness.process;

import java.util.Map;

public final class Utf8Process {
    private Utf8Process() {
    }

    public static void apply(ProcessBuilder builder) {
        Map<String, String> env = builder.environment();
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
        env.put("JAVA_TOOL_OPTIONS", appendOption(env.get("JAVA_TOOL_OPTIONS"), "-Dfile.encoding=UTF-8"));
        env.put("LC_ALL", "C.UTF-8");
        env.put("LANG", "C.UTF-8");
    }

    private static String appendOption(String value, String option) {
        if (value == null || value.trim().isEmpty()) {
            return option;
        }
        if (value.contains(option)) {
            return value;
        }
        return value + " " + option;
    }
}
