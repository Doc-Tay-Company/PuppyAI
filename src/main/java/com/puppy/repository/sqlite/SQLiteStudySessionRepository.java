package com.puppy.repository.sqlite;

import com.puppy.model.StudySession;
import com.puppy.persistence.Database;
import com.puppy.persistence.RepositoryException;
import com.puppy.repository.StudySessionRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite-backed {@link StudySessionRepository}.
 */
public final class SQLiteStudySessionRepository implements StudySessionRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, started_at, completed_at, duration_seconds, status FROM study_sessions ";

    private final Database database;

    public SQLiteStudySessionRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void save(StudySession session) {
        Objects.requireNonNull(session, "session");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO study_sessions (id, started_at, completed_at, duration_seconds, status) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, session.id());
                statement.setString(2, session.startedAt().toString());
                statement.setString(3, session.completedAt() == null ? null : session.completedAt().toString());
                statement.setLong(4, session.durationSeconds());
                statement.setString(5, session.status().name());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void update(StudySession session) {
        Objects.requireNonNull(session, "session");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE study_sessions SET completed_at = ?, duration_seconds = ?, status = ? "
                            + "WHERE id = ?")) {
                statement.setString(1, session.completedAt() == null ? null : session.completedAt().toString());
                statement.setLong(2, session.durationSeconds());
                statement.setString(3, session.status().name());
                statement.setString(4, session.id());
                if (statement.executeUpdate() != 1) {
                    throw new RepositoryException("Study session not found: " + session.id());
                }
            }
            return null;
        });
    }

    @Override
    public Optional<StudySession> findById(String id) {
        Objects.requireNonNull(id, "id");
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    SELECT_COLUMNS + "WHERE id = ?")) {
                statement.setString(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? Optional.of(RowMappers.studySession(result))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<StudySession> findRecent(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return database.query(connection -> {
            List<StudySession> sessions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    SELECT_COLUMNS + "ORDER BY started_at DESC, rowid DESC LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        sessions.add(RowMappers.studySession(result));
                    }
                }
            }
            return List.copyOf(sessions);
        });
    }
}
