package com.ahi.harness.session;

import com.ahi.harness.core.Message;

public interface SessionStore {
    void append(String type, String content) throws Exception;

    void appendMessage(Message message) throws Exception;
}
