package com.puppy.service;

/** User-facing failure from the isolated task-refinement flow. */
public final class TaskRefinementException extends Exception {
    public TaskRefinementException(String message) {
        super(message);
    }

    public TaskRefinementException(String message, Throwable cause) {
        super(message, cause);
    }
}
