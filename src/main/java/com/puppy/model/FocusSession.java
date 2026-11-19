package com.puppy.model;

import com.puppy.enums.FocusType;
import com.puppy.enums.FocusSessionState;
import com.puppy.enums.Priority;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * A scheduled block of focused study, created by the planner or by hand.
 * Instances are immutable; completing a session replaces it.
 */
public record FocusSession(
        String id,
        String subject,
        String title,
        FocusType type,
        LocalDate date,
        LocalTime start,
        int durationMinutes,
        Priority priority,
        boolean completed,
        FocusSessionState state
) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    public FocusSession {
        id = id == null ? "" : id.strip();
        subject = subject == null ? "" : subject.strip();
        title = title == null ? "" : title.strip();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("A session needs an id");
        }
        if (subject.isEmpty()) {
            throw new IllegalArgumentException("A session needs a subject");
        }
        if (title.isEmpty()) {
            throw new IllegalArgumentException("A session needs a title");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(priority, "priority");
        state = state == null
                ? (completed ? FocusSessionState.COMPLETED : FocusSessionState.PENDING)
                : state;
        completed = state == FocusSessionState.COMPLETED;
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException("A session must last at least one minute");
        }
        if ((long) start.toSecondOfDay() + durationMinutes * 60L > 24 * 60 * 60L) {
            throw new IllegalArgumentException("A session must end before midnight");
        }
    }

    /** Compatibility constructor for plans saved before lifecycle states existed. */
    public FocusSession(String id, String subject, String title, FocusType type,
                        LocalDate date, LocalTime start, int durationMinutes,
                        Priority priority, boolean completed) {
        this(id, subject, title, type, date, start, durationMinutes, priority,
                completed, completed ? FocusSessionState.COMPLETED : FocusSessionState.PENDING);
    }

    public LocalTime end() {
        return start.plusMinutes(durationMinutes);
    }

    public String timeRange() {
        return TIME.format(start) + " - " + TIME.format(end());
    }

    public FocusSession asCompleted() {
        return new FocusSession(id, subject, title, type, date, start,
                durationMinutes, priority, true, FocusSessionState.COMPLETED);
    }

    public FocusSession withState(FocusSessionState newState) {
        return new FocusSession(id, subject, title, type, date, start,
                durationMinutes, priority, newState == FocusSessionState.COMPLETED, newState);
    }
}
