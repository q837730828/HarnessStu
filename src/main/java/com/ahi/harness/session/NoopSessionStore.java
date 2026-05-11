package com.ahi.harness.session;

import com.ahi.harness.core.Message;

public class NoopSessionStore implements SessionStore {
    @Override
    public void append(String type, String content) {
    }

    @Override
    public void appendMessage(Message message) {
    }
}
