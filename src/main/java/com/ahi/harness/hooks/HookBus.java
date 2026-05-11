package com.ahi.harness.hooks;

import com.ahi.harness.ConsoleLog;

public class HookBus {
    private final ConsoleLog log;

    public HookBus(ConsoleLog log) {
        this.log = log;
    }

    public void emit(String event, String detail) {
        log.info("HOOK", event + (detail == null || detail.trim().isEmpty() ? "" : " | " + detail));
    }
}
