package com.puppy.ai;

/**
 * A failure while talking to Ollama.
 *
 * <p>{@link #getMessage()} is safe to show in the chat UI; raw exception detail
 * stays in {@link #getCause()} so no stack trace reaches the user.</p>
 */
public final class OllamaException extends Exception {

    public enum Reason {
        UNAVAILABLE,
        MODEL_NOT_FOUND,
        TIMEOUT,
        INTERRUPTED,
        PROTOCOL,
        HTTP
    }

    private final Reason reason;

    public OllamaException(Reason reason, String userMessage) {
        super(userMessage);
        this.reason = reason;
    }

    public OllamaException(Reason reason, String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
