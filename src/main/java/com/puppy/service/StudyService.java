package com.puppy.service;

import com.puppy.enums.SessionStatus;
import com.puppy.model.StudySession;
import com.puppy.persistence.RepositoryException;
import com.puppy.repository.StudySessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the Pomodoro countdown and records each session.
 *
 * <p>The service wraps {@link StudyTimer} so the controller only renders state: it
 * starts, pauses and resumes the timer while writing the matching
 * {@link StudySession} row. A session that is reset or left running at shutdown is
 * marked {@code ABANDONED}, so no row stays {@code RUNNING} after the app exits.</p>
 */
public final class StudyService implements AutoCloseable {

    private final StudySessionRepository sessions;
    private final Clock clock;
    private final StudyTimer timer;

    private StudySession current;

    public StudyService(StudySessionRepository sessions, Clock clock) {
        this(sessions, clock, new StudyTimer());
    }

    StudyService(StudySessionRepository sessions, Clock clock, StudyTimer timer) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /**
     * Starts the current phase. A focus phase begins a recorded session; the break
     * after it is part of the same focus session, so no second row is created for it.
     */
    public void start() {
        if (timer.getPhase() == StudyTimer.Phase.BREAK && !timer.isStarted()) {
            timer.start();
            return;
        }
        abandonCurrentSession();
        Instant now = clock.instant();
        current = new StudySession(
                UUID.randomUUID().toString(), now, null, 0, SessionStatus.RUNNING);
        sessions.save(current);
        timer.reset();
        timer.start();
    }

    public void pause() {
        if (!timer.isRunning()) {
            return;
        }
        timer.pause();
        updateCurrentSession(SessionStatus.PAUSED, timer.getElapsedSeconds());
    }

    public void resume() {
        if (timer.isRunning() || timer.isFinished()) {
            return;
        }
        if (current == null) {
            start();
            return;
        }
        timer.start();
        updateCurrentSession(SessionStatus.RUNNING, timer.getElapsedSeconds());
    }

    /**
     * Records the finished focus phase (if one is being tracked) and advances the
     * timer. Must be called once {@link #isFinished()} becomes true.
     */
    public void completePhase() {
        if (!timer.isFinished()) {
            throw new IllegalStateException("The current phase has not finished");
        }
        if (timer.getPhase() == StudyTimer.Phase.FOCUS) {
            finishCurrentSession(SessionStatus.COMPLETED, timer.getPhaseSeconds());
        }
        timer.completePhase();
    }

    /** Abandons a live session and returns the timer to a fresh focus phase. */
    public void reset() {
        abandonCurrentSession();
        timer.reset();
    }

    public int remainingSeconds() {
        return timer.getRemainingSeconds();
    }

    public StudyTimer.Phase phase() {
        return timer.getPhase();
    }

    public boolean isRunning() {
        return timer.isRunning();
    }

    public boolean isStarted() {
        return timer.isStarted();
    }

    public boolean isFinished() {
        return timer.isFinished();
    }

    /** The session being tracked, if the user has started one since the last reset. */
    public Optional<StudySession> currentSession() {
        return Optional.ofNullable(current);
    }

    /** Marks an unfinished session as abandoned when the application exits. */
    @Override
    public void close() {
        abandonCurrentSession();
    }

    private void updateCurrentSession(SessionStatus status, int durationSeconds) {
        if (current == null) {
            return;
        }
        current = new StudySession(
                current.id(), current.startedAt(), null, durationSeconds, status);
        sessions.update(current);
    }

    private void finishCurrentSession(SessionStatus status, int durationSeconds) {
        if (current == null) {
            return;
        }
        current = new StudySession(
                current.id(), current.startedAt(), clock.instant(), durationSeconds, status);
        sessions.update(current);
        current = null;
    }

    private void abandonCurrentSession() {
        if (current == null) {
            return;
        }
        finishCurrentSession(SessionStatus.ABANDONED, timer.getElapsedSeconds());
    }
}
