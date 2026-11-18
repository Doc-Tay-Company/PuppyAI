package com.puppy.config;

import com.puppy.database.Database;
import com.puppy.database.PersistenceException;
import com.puppy.repository.SettingsRepository;
import com.puppy.service.AppSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSettingsTest {
    @TempDir Path directory;

    @Test
    void migratesModelOnceWithoutCopyingSecretOrChangingLegacyFiles() throws Exception {
        var legacy = new AiSettingsStore(directory);
        legacy.saveSelectedModelId("old-model");
        String secret = "test-only-key-value-1234567890";
        legacy.saveOpenRouterApiKey(secret);
        byte[] original = Files.readAllBytes(directory.resolve("ai.properties"));
        try (Database database = Database.open(directory)) {
            var settings = new SettingsRepository(database);
            var migrated = new AiSettingsStore(directory, settings);
            assertEquals(Optional.of("old-model"), migrated.selectedModelId());
            assertEquals(Optional.of(secret), migrated.storedOpenRouterApiKey());
            migrated.saveSelectedModelId("new-model");
            assertArrayEquals(original, Files.readAllBytes(directory.resolve("ai.properties")));
            assertEquals(Optional.of("new-model"), new AiSettingsStore(directory, settings).selectedModelId());
            assertEquals(0, database.call(connection -> {
                try (var query = connection.prepareStatement("SELECT count(*) FROM settings WHERE value=?")) {
                    query.setString(1, secret);
                    try (var rows = query.executeQuery()) { return rows.getInt(1); }
                }
            }).intValue());
        }
        assertFalse(Files.exists(directory.resolve(".env")));
    }

    @Test
    void failedMigrationIsAtomicAndRetryable() throws Exception {
        new AiSettingsStore(directory).saveSelectedModelId("legacy");
        try (Database database = Database.open(directory)) {
            database.call(connection -> {
                try (var sql = connection.createStatement()) {
                    sql.execute("CREATE TRIGGER fail_marker BEFORE INSERT ON settings WHEN NEW.key='legacyModelImported' BEGIN SELECT RAISE(ABORT,'injected'); END");
                }
                return null;
            });
            var settings = new SettingsRepository(database);
            assertThrows(PersistenceException.class, () -> new AiSettingsStore(directory, settings));
            assertTrue(settings.get("selectedModel").isEmpty());
            assertTrue(settings.get("legacyModelImported").isEmpty());
            database.call(connection -> {
                try (var sql = connection.createStatement()) { sql.execute("DROP TRIGGER fail_marker"); }
                return null;
            });
            assertEquals(Optional.of("legacy"), new AiSettingsStore(directory, settings).selectedModelId());
        }
    }

    @Test
    void desktopSettingRestoresAndOnlyPublishesCommittedValues() {
        AppConfig config = new AppConfig("Puppy", "test", true, directory,
                AppConfig.DEFAULT_OLLAMA_URL, AppConfig.DEFAULT_OLLAMA_MODEL,
                null, AppConfig.DEFAULT_OPENROUTER_BASE_URL);
        try (Database database = Database.open(directory)) {
            var settings = new SettingsRepository(database);
            var service = new AppSettingsService(config, settings);
            service.setAlwaysOnTop(false);
            assertFalse(new AppSettingsService(config, settings).alwaysOnTop());
            database.call(connection -> {
                try (var sql = connection.createStatement()) {
                    sql.execute("CREATE TRIGGER fail_setting BEFORE UPDATE ON settings BEGIN SELECT RAISE(ABORT,'injected'); END");
                }
                return null;
            });
            assertThrows(PersistenceException.class, () -> service.setAlwaysOnTop(true));
            assertFalse(service.alwaysOnTop());
        }
    }

    @Test
    void explicitLaunchOverridesWinOverSavedPreferencesAndDataLocation() {
        String oldDirectory = System.getProperty("puppy.dataDirectory");
        String oldOnTop = System.getProperty("puppy.alwaysOnTop");
        try (Database database = Database.open(directory)) {
            var settings = new SettingsRepository(database);
            settings.put("alwaysOnTop", "true");
            System.setProperty("puppy.dataDirectory", directory.toString());
            System.setProperty("puppy.alwaysOnTop", "false");
            AppConfig config = AppConfig.load();
            assertEquals(directory.toAbsolutePath().normalize(), config.dataDirectory());
            assertFalse(new AppSettingsService(config, settings).alwaysOnTop());
            assertEquals(Optional.of("true"), settings.get("alwaysOnTop"),
                    "A launch override must not replace the persisted preference");
        } finally {
            if (oldDirectory == null) {
                System.clearProperty("puppy.dataDirectory");
            } else {
                System.setProperty("puppy.dataDirectory", oldDirectory);
            }
            if (oldOnTop == null) {
                System.clearProperty("puppy.alwaysOnTop");
            } else {
                System.setProperty("puppy.alwaysOnTop", oldOnTop);
            }
        }
    }
}
