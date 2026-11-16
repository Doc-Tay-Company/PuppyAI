package com.puppy.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Versioned schema for the SQLite database.
 *
 * <p>The current version is stored in SQLite's own {@code PRAGMA user_version}, so no
 * extra bookkeeping table is needed. Each future milestone adds a new version block
 * and bumps {@link #VERSION}; existing databases are upgraded in place on startup.</p>
 */
public final class Schema {

    /** Schema version created by this milestone. */
    public static final int VERSION = 1;

    private static final List<String> VERSION_1 = List.of(
            """
            CREATE TABLE conversations (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE study_sessions (
                id TEXT PRIMARY KEY,
                started_at TEXT NOT NULL,
                completed_at TEXT,
                duration_seconds INTEGER NOT NULL,
                status TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at)",
            "CREATE INDEX idx_conversations_updated ON conversations(updated_at DESC)");

    private Schema() {
    }

    static void migrate(Connection connection) throws SQLException {
        int version = readVersion(connection);
        if (version > VERSION) {
            throw new RepositoryException(
                    "Database schema version " + version + " is newer than supported version " + VERSION);
        }
        if (version == VERSION) {
            return;
        }

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (version < 1) {
                createVersion1(connection);
            }
            setVersion(connection, VERSION);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static int readVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void createVersion1(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : VERSION_1) {
                statement.execute(sql);
            }
        }
    }

    private static void setVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        }
    }
}
