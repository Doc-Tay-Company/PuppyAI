package com.puppy.service;

/**
 * A friendly failure while reading timetable/task files or building a plan.
 * The message is safe to show in the Focus panel or a chat bubble.
 */
public class FocusPlanningException extends Exception {

    public FocusPlanningException(String message) {
        super(message);
    }

    public FocusPlanningException(String message, Throwable cause) {
        super(message, cause);
    }
}
