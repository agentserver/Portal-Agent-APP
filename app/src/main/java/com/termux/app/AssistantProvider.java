package com.termux.app;

public enum AssistantProvider {
    CLAUDE("claude"),
    CODEX("codex");

    public final String id;

    AssistantProvider(String id) {
        this.id = id;
    }

    public static AssistantProvider fromId(String id) {
        if (CODEX.id.equals(id)) return CODEX;
        return CLAUDE;
    }
}
