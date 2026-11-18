package com.puppy.ai;

/**
 * A failure while talking to an AI provider.
 *
 * <p>{@link #getMessage()} is safe to show in the chat UI; raw exception detail
 * stays in {@link #getCause()} so no stack trace or credential reaches the
 * user. Providers must never include API keys in these messages.</p>
 */
public final class AiException extends Exception {

    public enum Reason {
        UNAVAILABLE,
        MODEL_NOT_FOUND,
        AUTHENTICATION,
        RATE_LIMIT,
        TIMEOUT,
        INTERRUPTED,
        PROTOCOL,
        HTTP
    }

    private final Reason reason;

    public AiException(Reason reason, String userMessage) {
        super(userMessage);
        this.reason = reason;
    }

    public AiException(Reason reason, String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
