package com.puppy.enums;

/** Roles understood by the Ollama chat API. */
public enum MessageRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    private final String wireName;

    MessageRole(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
