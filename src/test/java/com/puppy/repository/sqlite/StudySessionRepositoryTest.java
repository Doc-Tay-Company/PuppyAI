package com.puppy.repository.sqlite;

import com.puppy.enums.SessionStatus;
import com.puppy.model.StudySession;
import com.puppy.persistence.Database;
import com.puppy.persistence.RepositoryException;
import com.puppy.repository.StudySessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises recorded study sessions from start to finish.
 */
class StudySessionRepositoryTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");

    @TempDir
    Path directory;

    private Database database;
    private StudySessionRepository sessions;

    @BeforeEach
    void setUp() {
        database = new Database(directory.resolve("test.db"));
        database.initialize();
        sessions = new SQLiteStudySessionRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void savesARunningSession() {
        sessions.save(running("s1", START));

        StudySession stored = sessions.findById("s1").orElseThrow();

        assertEquals(START, stored.startedAt());
        assertNull(stored.completedAt());
        assertEquals(0, stored.durationSeconds());
        assertEquals(SessionStatus.RUNNING, stored.status());
    }

    @Test
    void updatingASessionPersistsItsFinalState() {
        sessions.save(running("s1", START));
        Instant finished = START.plusSeconds(1500);

        sessions.update(new StudySession("s1", START, finished, 1500, SessionStatus.COMPLETED));

        StudySession stored = sessions.findById("s1").orElseThrow();
        assertEquals(SessionStatus.COMPLETED, stored.status());
        assertEquals(finished, stored.completedAt());
        assertEquals(1500, stored.durationSeconds());
    }

    @Test
    void findRecentReturnsNewestFirst() {
        sessions.save(running("old", START));
        sessions.save(running("new", START.plusSeconds(3600)));

        List<StudySession> recent = sessions.findRecent(10);

        assertEquals(List.of("new", "old"), recent.stream().map(StudySession::id).toList());
        assertEquals(1, sessions.findRecent(1).size());
    }

    @Test
    void findByIdReturnsEmptyForAnUnknownSession() {
        assertTrue(sessions.findById("missing").isEmpty());
    }

    @Test
    void updatingAnUnknownSessionFails() {
        StudySession session = new StudySession("missing", START, START, 60, SessionStatus.ABANDONED);

        assertThrows(RepositoryException.class, () -> sessions.update(session));
    }

    private static StudySession running(String id, Instant startedAt) {
        return new StudySession(id, startedAt, null, 0, SessionStatus.RUNNING);
    }
}
