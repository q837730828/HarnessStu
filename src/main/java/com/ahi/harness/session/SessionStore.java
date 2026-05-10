package com.ahi.harness.session;

public interface SessionStore {
    void append(String type, String content) throws Exception;
}
