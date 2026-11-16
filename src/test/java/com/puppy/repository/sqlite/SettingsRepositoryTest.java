package com.puppy.repository.sqlite;

import com.puppy.persistence.Database;
import com.puppy.repository.SettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the key/value settings store.
 */
class SettingsRepositoryTest {

    @TempDir
    Path directory;

    private Database database;
    private SettingsRepository settings;

    @BeforeEach
    void setUp() {
        database = new Database(directory.resolve("test.db"));
        database.initialize();
        settings = new SQLiteSettingsRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void returnsEmptyForAMissingKey() {
        assertTrue(settings.get("missing").isEmpty());
    }

    @Test
    void storesAndReadsAValue() {
        settings.put("alwaysOnTop", "false");

        assertEquals("false", settings.get("alwaysOnTop").orElseThrow());
    }

    @Test
    void puttingTheSameKeyAgainReplacesTheValue() {
        settings.put("alwaysOnTop", "true");
        settings.put("alwaysOnTop", "false");

        assertEquals(Map.of("alwaysOnTop", "false"), settings.getAll());
    }

    @Test
    void returnsEverySettingSortedByKey() {
        settings.put("b", "2");
        settings.put("a", "1");

        assertEquals(Map.of("a", "1", "b", "2"), settings.getAll());
        assertEquals(java.util.List.of("a", "b"), settings.getAll().keySet().stream().toList());
    }
}
