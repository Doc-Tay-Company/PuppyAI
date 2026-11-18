package com.puppy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSettingsStoreTest {

    private static final String KEY = "sk-or-v1-test-key-1234567890";

    @TempDir
    Path tempDirectory;

    @Test
    void savesTheSelectedModelInTheLocalDataDirectory() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);

        store.saveSelectedModelId("openrouter:google/gemini-2.5-flash");

        AiSettingsStore reloaded = new AiSettingsStore(tempDirectory);
        assertEquals(Optional.of("openrouter:google/gemini-2.5-flash"),
                reloaded.selectedModelId());
        assertTrue(Files.isRegularFile(tempDirectory.resolve(AiSettingsStore.SETTINGS_FILE)));
    }

    @Test
    void keepsTheApiKeyInASeparateFile() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);

        store.saveOpenRouterApiKey(KEY);
        store.saveSelectedModelId("openrouter:google/gemini-2.5-flash");

        assertEquals(Optional.of(KEY),
                new AiSettingsStore(tempDirectory).storedOpenRouterApiKey());
        assertTrue(Files.isRegularFile(store.secretsFile()));
        String settings = Files.readString(
                tempDirectory.resolve(AiSettingsStore.SETTINGS_FILE));
        assertFalse(settings.contains(KEY),
                "The model-selection file must not contain the API key");
    }

    @Test
    void rejectsBlankAndObviousPlaceholderKeys() {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);

        assertThrows(IllegalArgumentException.class, () -> store.saveOpenRouterApiKey("   "));
        assertThrows(IllegalArgumentException.class, () -> store.saveOpenRouterApiKey("short"));
        assertThrows(IllegalArgumentException.class,
                () -> store.saveOpenRouterApiKey("key with spaces that is long enough"));
    }

    @Test
    void clearsTheKeyAndDeletesTheSecretFile() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);
        store.saveOpenRouterApiKey(KEY);
        assertTrue(Files.isRegularFile(store.secretsFile()));

        store.clearOpenRouterApiKey();

        assertEquals(Optional.empty(),
                new AiSettingsStore(tempDirectory).storedOpenRouterApiKey());
        assertFalse(Files.exists(store.secretsFile()));
    }

    @Test
    void damagedFilesFallBackToDefaultsInsteadOfCrashing() throws Exception {
        Files.writeString(tempDirectory.resolve(AiSettingsStore.SETTINGS_FILE),
                "selectedModel=\\uZZZZ\n");
        Files.writeString(tempDirectory.resolve(AiSettingsStore.SECRETS_FILE),
                "openrouter.apiKey=\\uZZZZ\n");

        AiSettingsStore store = new AiSettingsStore(tempDirectory);

        assertEquals(Optional.empty(), store.selectedModelId());
        assertEquals(Optional.empty(), store.storedOpenRouterApiKey());
    }

    @Test
    void cachesTheCatalogAndItsFetchTime() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);
        Instant fetchedAt = Instant.parse("2026-11-18T09:30:00Z");

        store.saveOpenRouterCatalog("{\"data\":[]}", fetchedAt);

        AiSettingsStore reloaded = new AiSettingsStore(tempDirectory);
        assertEquals(Optional.of("{\"data\":[]}"), reloaded.openRouterCatalog());
        assertEquals(Optional.of(fetchedAt), reloaded.openRouterCatalogFetchedAt());
    }

    @Test
    void remembersRecentModelsNewestFirstAndBounded() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);

        store.rememberRecentModelId("openrouter:a/one");
        store.rememberRecentModelId("openrouter:a/two");
        store.rememberRecentModelId("openrouter:a/three");
        store.rememberRecentModelId("openrouter:a/four");
        store.rememberRecentModelId("openrouter:a/five");
        store.rememberRecentModelId("openrouter:a/six");
        store.rememberRecentModelId("openrouter:a/two");

        List<String> recent = new AiSettingsStore(tempDirectory).recentModelIds();
        assertEquals(5, recent.size());
        assertEquals("openrouter:a/two", recent.get(0));
        assertEquals("openrouter:a/six", recent.get(1));
        assertFalse(recent.contains("openrouter:a/one"));
    }
}
