package com.puppy.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A weekly school class. Focus sessions are never placed over one of these
 * slots, including a small guard around the exact times.
 */
public record ClassSlot(String subject, DayOfWeek day, LocalTime start, LocalTime end) {

    public ClassSlot {
        subject = subject == null ? "" : subject.strip();
        if (subject.isEmpty()) {
            throw new IllegalArgumentException("A class needs a subject");
        }
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("A class must end after it starts");
        }
    }

    public int durationMinutes() {
        return (int) java.time.Duration.between(start, end).toMinutes();
    }

    /** True when the interval overlaps this class on the given weekday. */
    public boolean overlaps(DayOfWeek otherDay, LocalTime otherStart, LocalTime otherEnd) {
        return day == otherDay && otherStart.isBefore(end) && start.isBefore(otherEnd);
    }
}
