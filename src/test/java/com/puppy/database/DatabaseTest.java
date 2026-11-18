package com.puppy.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {
    @TempDir Path directory;

    @Test
    void locksDirectoryRecoversInterruptedRepliesAndReleasesOnClose() throws Exception {
        try (Database database = Database.open(directory)) {
            assertThrows(PersistenceException.class, () -> Database.open(directory));
            database.transaction(connection -> {
                try (var sql = connection.createStatement()) {
                    sql.executeUpdate("INSERT INTO conversations VALUES('c','title',1,'COMPLETE')");
                    sql.executeUpdate("INSERT INTO messages VALUES('m','c',0,'assistant','partial','[]',0,'GENERATING','TEXT')");
                }
                return null;
            });
        }
        try (Database database = Database.open(directory)) {
            assertEquals("INTERRUPTED", database.call(connection -> {
                try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT status FROM messages")) {
                    return rows.getString(1);
                }
            }));
            assertEquals(1, database.call(connection -> {
                try (var sql = connection.createStatement(); var rows = sql.executeQuery("PRAGMA secure_delete")) {
                    return rows.getInt(1);
                }
            }).intValue());
        }
    }

    @Test
    void failedTransactionsRollbackAndForeignKeysAreEnabled() {
        try (Database database = Database.open(directory)) {
            assertThrows(PersistenceException.class, () -> database.transaction(connection -> {
                try (var sql = connection.createStatement()) {
                    sql.executeUpdate("INSERT INTO settings VALUES('partial','no')");
                    sql.executeUpdate("INSERT INTO attachments VALUES('missing',0,'file.txt','text',0,0)");
                }
                return null;
            }));
            assertEquals(0, database.call(connection -> {
                try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM settings")) {
                    return rows.getInt(1);
                }
            }).intValue());
        }
    }

    @Test
    void upgradesWithBackupAndRejectsNewerSchemaWithoutChangingIt() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("puppy.db"));
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE legacy_test(value TEXT)");
            sql.execute("INSERT INTO legacy_test VALUES('preserve')");
        }
        try (Database ignored = Database.open(directory)) {
            assertTrue(Files.exists(directory.resolve("puppy.pre-upgrade.db")));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("puppy.db"));
             var sql = connection.createStatement()) {
            sql.execute("PRAGMA user_version=999");
        }
        byte[] original = Files.readAllBytes(directory.resolve("puppy.db"));
        assertThrows(PersistenceException.class, () -> Database.open(directory));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("puppy.db")));
    }

    @Test
    void failedMigrationLeavesOriginalSchemaAndBackup() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("puppy.db"));
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE settings(old_column TEXT)");
            sql.execute("INSERT INTO settings VALUES('preserve')");
        }
        assertThrows(PersistenceException.class, () -> Database.open(directory));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("puppy.db"));
             var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT old_column FROM settings")) {
            assertEquals("preserve", rows.getString(1));
        }
        assertTrue(Files.exists(directory.resolve("puppy.pre-upgrade.db")));
    }
}
