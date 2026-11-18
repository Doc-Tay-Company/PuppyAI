package com.puppy.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Stores the selected model and the OpenRouter API key in the user's local data
 * directory.
 *
 * <p>Nothing here lives in the project or is written to logs. API keys go in a
 * separate file whose owner-only permissions are applied when the platform
 * supports POSIX permissions. Reads are deliberately forgiving: a missing or
 * damaged settings file simply means Puppy falls back to defaults.</p>
 */
public final class AiSettingsStore {

    static final String SETTINGS_FILE = "ai.properties";
    static final String SECRETS_FILE = "ai-secrets.properties";
    private static final String SELECTED_MODEL_KEY = "selectedModel";
    private static final String OPENROUTER_KEY = "openrouter.apiKey";
    private static final String CATALOG_KEY = "openRouterCatalog";
    private static final String CATALOG_FETCHED_AT_KEY = "openRouterCatalogFetchedAt";
    private static final String RECENT_MODELS_KEY = "recentModels";
    private static final int RECENT_MODELS_LIMIT = 5;

    private final Path dataDirectory;
    private final Path settingsFile;
    private final Path secretsFile;
    private com.puppy.repository.SettingsRepository repository;
    private Optional<String> selectedModel;

    public AiSettingsStore(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath()
                .normalize();
        this.settingsFile = this.dataDirectory.resolve(SETTINGS_FILE);
        this.secretsFile = this.dataDirectory.resolve(SECRETS_FILE);
    }

    public AiSettingsStore(Path dataDirectory, com.puppy.repository.SettingsRepository repository) {
        this(dataDirectory);
        this.repository = Objects.requireNonNull(repository, "repository");
        if (repository.get("legacyModelImported").isEmpty()) {
            Properties legacy = new Properties();
            if (Files.exists(settingsFile)) {
                try (InputStream input = Files.newInputStream(settingsFile)) {
                    legacy.load(input);
                } catch (IOException | IllegalArgumentException e) {
                    throw new com.puppy.database.PersistenceException(
                            "Cannot import the old model setting. Repair ai.properties and retry; it was not changed.", e);
                }
            }
            String model = legacy.getProperty(SELECTED_MODEL_KEY);
            repository.importLegacyModel(model);
        }
        selectedModel = repository.get(SELECTED_MODEL_KEY);
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Path secretsFile() {
        return secretsFile;
    }

    /** The model the user explicitly chose; missing catalog entries are still kept. */
    public Optional<String> selectedModelId() {
        if (repository != null) {
            return selectedModel;
        }
        return Optional.ofNullable(read(settingsFile).getProperty(SELECTED_MODEL_KEY))
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }

    public void saveSelectedModelId(String modelId) throws IOException {
        String cleaned = modelId == null ? "" : modelId.strip();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("A model must be selected");
        }
        if (repository == null) {
            writeProperty(settingsFile, SELECTED_MODEL_KEY, cleaned);
        } else {
            repository.put(SELECTED_MODEL_KEY, cleaned);
            selectedModel = Optional.of(cleaned);
        }
    }

    /**
     * Recently used model ids, newest first. Used by the compact Settings
     * picker so the full catalog can stay in the browse dialog.
     */
    public List<String> recentModelIds() {
        String value = readValue(RECENT_MODELS_KEY).orElse("");
        Set<String> unique = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String cleaned = part.strip();
            if (!cleaned.isEmpty()) {
                unique.add(cleaned);
            }
            if (unique.size() >= RECENT_MODELS_LIMIT) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    /** Remembers a model choice for the short picker, keeping the list bounded. */
    public void rememberRecentModelId(String modelId) throws IOException {
        String cleaned = modelId == null ? "" : modelId.strip();
        if (cleaned.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(recentModelIds());
        ids.remove(cleaned);
        ids.add(0, cleaned);
        if (ids.size() > RECENT_MODELS_LIMIT) {
            ids = new ArrayList<>(ids.subList(0, RECENT_MODELS_LIMIT));
        }
        writeValue(RECENT_MODELS_KEY, String.join(",", ids));
    }

    /** The last raw OpenRouter {@code /models} response, kept for offline starts. */
    public Optional<String> openRouterCatalog() {
        return readValue(CATALOG_KEY).filter(value -> value.startsWith("{"));
    }

    /** When the cached catalog was fetched, if that timestamp is still valid. */
    public Optional<Instant> openRouterCatalogFetchedAt() {
        return readValue(CATALOG_FETCHED_AT_KEY).flatMap(value -> {
            try {
                return Optional.of(Instant.parse(value));
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        });
    }

    /** Saves the raw catalog and its timestamp together for the next start. */
    public void saveOpenRouterCatalog(String rawJson, Instant fetchedAt) throws IOException {
        String json = Objects.requireNonNull(rawJson, "rawJson");
        Instant timestamp = Objects.requireNonNull(fetchedAt, "fetchedAt");
        if (repository == null) {
            Properties properties = read(settingsFile);
            properties.setProperty(CATALOG_KEY, json);
            properties.setProperty(CATALOG_FETCHED_AT_KEY, timestamp.toString());
            writeProperties(settingsFile, properties);
        } else {
            repository.put(CATALOG_KEY, json);
            repository.put(CATALOG_FETCHED_AT_KEY, timestamp.toString());
        }
    }

    /** Returns the stored OpenRouter key, never the environment key. */
    public Optional<String> storedOpenRouterApiKey() {
        return Optional.ofNullable(read(secretsFile).getProperty(OPENROUTER_KEY))
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }

    public void saveOpenRouterApiKey(String apiKey) throws IOException {
        String cleaned = normalizeKey(apiKey);
        writeProperty(secretsFile, OPENROUTER_KEY, cleaned);
    }

    public void clearOpenRouterApiKey() throws IOException {
        Properties properties = read(secretsFile);
        if (properties.remove(OPENROUTER_KEY) == null) {
            return;
        }
        if (properties.isEmpty()) {
            Files.deleteIfExists(secretsFile);
            return;
        }
        writeProperties(secretsFile, properties);
    }

    static String normalizeKey(String apiKey) {
        String cleaned = apiKey == null ? "" : apiKey.strip();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Paste an OpenRouter API key first.");
        }
        if (cleaned.length() < 20) {
            throw new IllegalArgumentException(
                    "That key looks too short. Paste the full OpenRouter API key.");
        }
        if (cleaned.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "The OpenRouter API key should be one continuous value with no spaces.");
        }
        return cleaned;
    }

    private void writeProperty(Path file, String key, String value) throws IOException {
        Properties properties = read(file);
        properties.setProperty(key, value);
        writeProperties(file, properties);
    }

    /** Reads a non-secret setting from SQLite or the legacy properties file. */
    private Optional<String> readValue(String key) {
        if (repository != null) {
            return repository.get(key).map(String::strip).filter(value -> !value.isEmpty());
        }
        return Optional.ofNullable(read(settingsFile).getProperty(key))
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }

    /** Writes a non-secret setting to SQLite or the legacy properties file. */
    private void writeValue(String key, String value) throws IOException {
        if (repository == null) {
            writeProperty(settingsFile, key, value);
        } else {
            repository.put(key, value);
        }
    }

    private Properties read(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException | IllegalArgumentException e) {
            // A damaged local file must not stop the app from starting.
            return new Properties();
        }
        return properties;
    }

    private void writeProperties(Path file, Properties properties) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(
                file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                properties.store(output,
                        "Puppy AI local settings. Keep this file private.");
            }
            restrictPermissions(temporary);
            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(
                        file, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and some filesystems do not expose POSIX permissions.
            // The data directory is already scoped to the current user.
        }
    }
}
