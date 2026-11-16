package com.puppy.service;

import com.puppy.enums.AIMode;
import com.puppy.model.AppSettings;
import com.puppy.persistence.Database;
import com.puppy.repository.SettingsRepository;
import com.puppy.repository.sqlite.SQLiteSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises settings precedence and persistence.
 */
class SettingsServiceTest {

    @TempDir
    Path directory;

    private Database database;
    private SettingsRepository repository;
    private SettingsService settings;

    @BeforeEach
    void setUp() {
        database = new Database(directory.resolve("test.db"));
        database.initialize();
        repository = new SQLiteSettingsRepository(database);
        settings = new SettingsService(repository, Optional.empty());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void returnsDefaultsWhenNothingHasBeenSaved() {
        assertEquals(new AppSettings(true, AIMode.AUTO), settings.load());
        assertTrue(settings.effectiveAlwaysOnTop());
    }

    @Test
    void savedValuesAreLoaded() {
        repository.put(SettingsService.ALWAYS_ON_TOP_KEY, "false");
        repository.put(SettingsService.AI_MODE_KEY, "OFFLINE");

        assertEquals(new AppSettings(false, AIMode.OFFLINE), settings.load());
    }

    @Test
    void anExplicitOverrideOutranksTheStoredValue() {
        repository.put(SettingsService.ALWAYS_ON_TOP_KEY, "false");
        SettingsService overridden = new SettingsService(repository, Optional.of(true));

        assertTrue(overridden.load().alwaysOnTop());
    }

    @Test
    void malformedStoredValuesFallBackToDefaults() {
        repository.put(SettingsService.ALWAYS_ON_TOP_KEY, "sometimes");
        repository.put(SettingsService.AI_MODE_KEY, "TURBO");

        assertEquals(new AppSettings(true, AIMode.AUTO), settings.load());
    }

    @Test
    void settingAlwaysOnTopIsPersisted() {
        settings.setAlwaysOnTop(false);

        assertEquals("false", repository.get(SettingsService.ALWAYS_ON_TOP_KEY).orElseThrow());
        assertFalse(new SettingsService(repository, Optional.empty()).load().alwaysOnTop());
    }

    @Test
    void settingTheAiModeIsPersisted() {
        settings.setAiMode(AIMode.ONLINE);

        assertEquals(AIMode.ONLINE, settings.load().aiMode());
        assertEquals("ONLINE", repository.get(SettingsService.AI_MODE_KEY).orElseThrow());
    }
}
