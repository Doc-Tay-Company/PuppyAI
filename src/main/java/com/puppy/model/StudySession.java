package com.puppy.model;

import com.puppy.enums.SessionStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * One recorded Pomodoro session.
 *
 * @param startedAt       when the user started the focus phase
 * @param completedAt     when the session reached a final state, or {@code null}
 * @param durationSeconds elapsed seconds at the last state change
 * @param status          current lifecycle state
 */
public record StudySession(
        String id,
        Instant startedAt,
        Instant completedAt,
        long durationSeconds,
        SessionStatus status
) {

    public StudySession {
        id = Validate.text(id, "id");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        status = Objects.requireNonNull(status, "status");
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds must not be negative");
        }
        if (!status.isLive() && completedAt == null) {
            throw new IllegalArgumentException("finished sessions need completedAt");
        }
    }
}
