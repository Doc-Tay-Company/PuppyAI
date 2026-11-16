package com.puppy.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies schema creation, key pragmas and transaction handling against a real
 * SQLite file in a temporary directory.
 */
class DatabaseTest {

    private static final String CONVERSATION_INSERT =
            "INSERT INTO conversations (id, title, created_at, updated_at) "
                    + "VALUES ('c1', 'First chat', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')";

    @TempDir
    Path directory;

    private Database database;

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void createsAllTablesAndSchemaVersion() {
        database = openDatabase();

        assertEquals(1, userVersion());
        assertEquals(Set.of("conversations", "messages", "settings", "study_sessions"), tableNames());
        assertEquals(directory.resolve("puppy.db"), database.file());
    }

    @Test
    void initializeIsIdempotent() {
        database = openDatabase();

        database.initialize();

        assertEquals(1, userVersion());
    }

    @Test
    void foreignKeysAreEnforced() {
        database = openDatabase();

        RepositoryException failure = assertThrows(RepositoryException.class, () ->
                database.transaction(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO messages (id, conversation_id, role, content, created_at) "
                                    + "VALUES ('m1', 'missing', 'USER', 'hello', '2026-01-01T00:00:00Z')")) {
                        return statement.executeUpdate();
                    }
                }));

        assertTrue(failure.getCause() instanceof java.sql.SQLException);
    }

    @Test
    void runtimeFailureRollsTheTransactionBack() {
        database = openDatabase();

        assertThrows(IllegalStateException.class, () ->
                database.transaction(connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(CONVERSATION_INSERT);
                    }
                    throw new IllegalStateException("stop");
                }));

        assertEquals(0, countConversations());
    }

    @Test
    void dataSurvivesReopeningTheFile() {
        database = openDatabase();
        database.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(CONVERSATION_INSERT);
            }
            return null;
        });
        database.close();

        database = openDatabase();

        assertEquals(1, countConversations());
    }

    @Test
    void rejectsANewerSchemaVersion() {
        database = openDatabase();
        database.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = " + (Schema.VERSION + 1));
            }
            return null;
        });

        assertThrows(RepositoryException.class, database::initialize);
    }

    private Database openDatabase() {
        Database opened = new Database(directory.resolve("puppy.db"));
        opened.initialize();
        return opened;
    }

    private int userVersion() {
        return database.query(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                return result.next() ? result.getInt(1) : -1;
            }
        });
    }

    private Set<String> tableNames() {
        return database.query(connection -> {
            Set<String> names = new HashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type = 'table' "
                                 + "AND name NOT LIKE 'sqlite_%'")) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
            return names;
        });
    }

    private int countConversations() {
        return database.query(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM conversations")) {
                return result.next() ? result.getInt(1) : -1;
            }
        });
    }
}
