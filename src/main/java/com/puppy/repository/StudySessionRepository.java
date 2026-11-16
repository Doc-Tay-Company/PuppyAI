package com.puppy.repository;

import com.puppy.model.StudySession;

import java.util.List;
import java.util.Optional;

/**
 * Stores Pomodoro sessions and their lifecycle state.
 */
public interface StudySessionRepository {

    void save(StudySession session);

    /** Replaces the mutable fields of an existing session. */
    void update(StudySession session);

    Optional<StudySession> findById(String id);

    /** Newest first. */
    List<StudySession> findRecent(int limit);
}
