package com.puppy.model;

import com.puppy.util.SubjectNames;

import java.time.LocalDate;
import java.util.List;

/**
 * The outcome of one planning run: the sessions Puppy placed plus any
 * constraints it could not satisfy.
 */
public record FocusPlan(
        List<FocusSession> sessions,
        List<String> warnings,
        LocalDate generatedOn
) {

    public FocusPlan {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static FocusPlan empty(LocalDate today, String message) {
        return new FocusPlan(List.of(), List.of(message), today);
    }

    public int totalMinutes() {
        return sessions.stream().mapToInt(FocusSession::durationMinutes).sum();
    }

    public long completedCount() {
        return sessions.stream().filter(FocusSession::completed).count();
    }

    public long skippedCount() {
        return sessions.stream()
                .filter(session -> session.state() == com.puppy.enums.FocusSessionState.SKIPPED)
                .count();
    }

    public long subjectCount() {
        return sessions.stream()
                .map(session -> SubjectNames.key(session.subject()))
                .distinct()
                .count();
    }
}
