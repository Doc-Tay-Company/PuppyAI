package com.puppy.service;

/** A quiz failure whose message is safe to display; provider details stay in the cause. */
public final class QuizException extends Exception {
    public QuizException(String userMessage) {
        super(userMessage);
    }

    public QuizException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}
