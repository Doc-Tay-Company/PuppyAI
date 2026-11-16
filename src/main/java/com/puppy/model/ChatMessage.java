package com.puppy.model;

import com.puppy.enums.MessageRole;

import java.util.Objects;

/** One turn sent to the local model. */
public record ChatMessage(MessageRole role, String content) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(MessageRole.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(MessageRole.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(MessageRole.ASSISTANT, content);
    }
}
