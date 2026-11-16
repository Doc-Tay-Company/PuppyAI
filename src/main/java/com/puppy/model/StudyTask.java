package com.puppy.model;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One piece of work the planner should place before a deadline.
 *
 * <p>The estimated time is split into session-sized blocks; {@code deadline}
 * may be {@code null} for work without a due date.</p>
 */
public record StudyTask(
        String subject,
        String title,
        FocusType type,
        int estimatedMinutes,
        LocalDate deadline,
        Priority priority
) {

    public StudyTask {
        subject = subject == null ? "" : subject.strip();
        title = title == null ? "" : title.strip();
        if (subject.isEmpty()) {
            throw new IllegalArgumentException("A task needs a subject");
        }
        if (title.isEmpty()) {
            throw new IllegalArgumentException("A task needs a title");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(priority, "priority");
        if (estimatedMinutes <= 0) {
            throw new IllegalArgumentException("Estimated minutes must be positive");
        }
    }

    public StudyTask withType(FocusType newType) {
        return new StudyTask(subject, title, newType, estimatedMinutes, deadline, priority);
    }
}
