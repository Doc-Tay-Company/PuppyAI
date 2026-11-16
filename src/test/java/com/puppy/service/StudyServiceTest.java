package com.puppy.service;

import com.puppy.enums.SessionStatus;
import com.puppy.model.StudySession;
import com.puppy.persistence.Database;
import com.puppy.repository.StudySessionRepository;
import com.puppy.repository.sqlite.SQLiteStudySessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link StudyService} with a fake nano clock and a fixed wall clock, so the
 * recorded session lifecycle is checked without waiting for a real Pomodoro.
 */
class StudyServiceTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    @TempDir
    Path directory;

    private final AtomicLong clockNanos = new AtomicLong();
    private Database database;
    private StudySessionRepository repository;
    private StudyService study;

    @BeforeEach
    void setUp() {
        database = new Database(directory.resolve("test.db"));
        database.initialize();
        repository = new SQLiteStudySessionRepository(database);
        StudyTimer timer = new StudyTimer(60, 60, clockNanos::get);
        study = new StudyService(repository, Clock.fixed(START, ZoneOffset.UTC), timer);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void startingRecordsARunningSession() {
        study.start();

        StudySession session = study.currentSession().orElseThrow();
        assertEquals(SessionStatus.RUNNING, session.status());
        assertEquals(START, session.startedAt());
        assertEquals(0, session.durationSeconds());
        assertEquals(session, repository.findById(session.id()).orElseThrow());
        assertTrue(study.isRunning());
    }

    @Test
    void pausingRecordsTheElapsedTime() {
        study.start();
        advanceSeconds(10);

        study.pause();

        StudySession paused = study.currentSession().orElseThrow();
        assertEquals(SessionStatus.PAUSED, paused.status());
        assertEquals(10, paused.durationSeconds());
        assertEquals(SessionStatus.PAUSED, repository.findById(paused.id()).orElseThrow().status());
        assertFalse(study.isRunning());
    }

    @Test
    void resumingMarksTheSessionRunningAgain() {
        study.start();
        advanceSeconds(10);
        study.pause();

        study.resume();

        assertEquals(SessionStatus.RUNNING, study.currentSession().orElseThrow().status());
        assertTrue(study.isRunning());
        assertTrue(study.isStarted());
    }

    @Test
    void completingFocusRecordsACompletedSession() {
        study.start();
        advanceSeconds(60);
        assertTrue(study.isFinished());

        study.completePhase();

        StudySession completed = repository.findRecent(10).getFirst();
        assertEquals(SessionStatus.COMPLETED, completed.status());
        assertEquals(60, completed.durationSeconds());
        assertEquals(START, completed.completedAt());
        assertTrue(study.currentSession().isEmpty());
        assertEquals(StudyTimer.Phase.BREAK, study.phase());
    }

    @Test
    void theBreakAfterFocusDoesNotCreateAnotherSession() {
        study.start();
        advanceSeconds(60);
        study.completePhase();

        study.start(); // begin the break
        assertTrue(study.isRunning());
        assertTrue(study.currentSession().isEmpty());
        advanceSeconds(60);
        study.completePhase();

        assertEquals(1, repository.findRecent(10).size());
        assertEquals(StudyTimer.Phase.FOCUS, study.phase());
    }

    @Test
    void resettingAbandonsTheSession() {
        study.start();
        advanceSeconds(5);

        study.reset();

        StudySession abandoned = repository.findRecent(1).getFirst();
        assertEquals(SessionStatus.ABANDONED, abandoned.status());
        assertEquals(5, abandoned.durationSeconds());
        assertFalse(study.isStarted());
        assertEquals(StudyTimer.Phase.FOCUS, study.phase());
    }

    @Test
    void closingAbandonsALiveSession() {
        study.start();
        advanceSeconds(7);

        study.close();

        assertEquals(SessionStatus.ABANDONED, repository.findRecent(1).getFirst().status());
    }

    @Test
    void startingAgainAbandonsThePreviousSession() {
        study.start();
        advanceSeconds(3);
        String firstId = study.currentSession().orElseThrow().id();

        study.start();

        StudySession first = repository.findById(firstId).orElseThrow();
        assertEquals(SessionStatus.ABANDONED, first.status());
        assertEquals(3, first.durationSeconds());
        StudySession second = study.currentSession().orElseThrow();
        assertNotEquals(firstId, second.id());
        assertEquals(SessionStatus.RUNNING, second.status());
        assertEquals(2, repository.findRecent(10).size());
    }

    @Test
    void completingAPhaseThatHasNotFinishedFails() {
        study.start();

        assertThrows(IllegalStateException.class, study::completePhase);
    }

    private void advanceSeconds(int seconds) {
        clockNanos.addAndGet(seconds * NANOS_PER_SECOND);
    }
}
