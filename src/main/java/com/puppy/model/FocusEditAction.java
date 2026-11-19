package com.puppy.model;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * One edit action the AI wants to apply to the current study plan.
 *
 * <p>Session actions target a session by its one-based index in the numbered
 * plan the AI was shown. {@code ADD} carries a full session description and
 * {@code SET_WINDOW} changes the daily free-time window instead of a
 * session.</p>
 */
public record FocusEditAction(
        Type type,
        int sessionIndex,
        String subject,
        String title,
        FocusType focusType,
        LocalDate date,
        LocalTime start,
        Integer durationMinutes,
        Priority priority,
        LocalTime earliest,
        LocalTime latest
) {

    public enum Type {
        MOVE,
        RESIZE,
        REMOVE,
        CHANGE_SUBJECT,
        CHANGE_TYPE,
        ADD,
        ADD_TASK,
        REMOVE_TASK,
        PRIORITIZE_SUBJECT,
        SET_WINDOW,
        SET_BREAK,
        SET_SESSION_LIMIT,
        /** General patch kept for compatibility with older editor replies. */
        UPDATE
    }

    public FocusEditAction {
        Objects.requireNonNull(type, "type");
        if (targetsSession(type) && sessionIndex <= 0) {
            throw new IllegalArgumentException(
                    type + " actions need a positive sessionIndex");
        }
        if (type == Type.SET_WINDOW) {
            Objects.requireNonNull(earliest, "earliest");
            Objects.requireNonNull(latest, "latest");
            if (!earliest.isBefore(latest)) {
                throw new IllegalArgumentException(
                        "The free-time window must end after it starts");
            }
        }
    }

    /** Compatibility constructor for the original UPDATE/REMOVE/ADD shape. */
    public FocusEditAction(Type type, int sessionIndex, String subject, String title,
                           FocusType focusType, LocalDate date, LocalTime start,
                           Integer durationMinutes, Priority priority) {
        this(type, sessionIndex, subject, title, focusType, date, start,
                durationMinutes, priority, null, null);
    }

    public static boolean targetsSession(Type type) {
        return type == Type.MOVE || type == Type.RESIZE || type == Type.REMOVE
                || type == Type.CHANGE_SUBJECT || type == Type.CHANGE_TYPE
                || type == Type.UPDATE;
    }

    /** Convenience factory for a remove action. */
    public static FocusEditAction remove(int sessionIndex) {
        return new FocusEditAction(Type.REMOVE, sessionIndex,
                null, null, null, null, null, null, null);
    }

    /** Moves a session to another date and/or start time. */
    public static FocusEditAction move(int sessionIndex, LocalDate date, LocalTime start) {
        return new FocusEditAction(Type.MOVE, sessionIndex,
                null, null, null, date, start, null, null);
    }

    /** Changes how long a session lasts. */
    public static FocusEditAction resize(int sessionIndex, int durationMinutes) {
        return new FocusEditAction(Type.RESIZE, sessionIndex,
                null, null, null, null, null, durationMinutes, null);
    }

    /** Renames the subject of an existing session. */
    public static FocusEditAction changeSubject(int sessionIndex, String subject) {
        return new FocusEditAction(Type.CHANGE_SUBJECT, sessionIndex,
                subject, null, null, null, null, null, null);
    }

    /** Replaces the daily free-time window. */
    public static FocusEditAction setWindow(LocalTime earliest, LocalTime latest) {
        return new FocusEditAction(Type.SET_WINDOW, 0,
                null, null, null, null, null, null, null, earliest, latest);
    }

    public static FocusEditAction setBreak(int minutes) {
        return new FocusEditAction(Type.SET_BREAK, 0,
                null, null, null, null, null, minutes, null);
    }

    public static FocusEditAction setSessionLimit(int minutes) {
        return new FocusEditAction(Type.SET_SESSION_LIMIT, 0,
                null, null, null, null, null, minutes, null);
    }

    /** Convenience factory for an add action with full session details. */
    public static FocusEditAction add(String subject, String title, FocusType focusType,
                                      LocalDate date, LocalTime start, int durationMinutes,
                                      Priority priority) {
        return new FocusEditAction(Type.ADD, 0, subject, title, focusType,
                date, start, durationMinutes, priority);
    }
}
