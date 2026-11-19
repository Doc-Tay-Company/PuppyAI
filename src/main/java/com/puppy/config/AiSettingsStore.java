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
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

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
    private static final String OPENROUTER_MODEL_KEY = "openrouter.modelId";

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

    /** The model the user explicitly chose, if the model no longer exists this is ignored. */
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

    public Optional<String> openRouterModelId() {
        if (repository != null) {
            return repository.get(OPENROUTER_MODEL_KEY);
        }
        return Optional.ofNullable(read(settingsFile).getProperty(OPENROUTER_MODEL_KEY))
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }

    public void saveOpenRouterModelId(String modelId) throws IOException {
        String cleaned = modelId == null ? "" : modelId.strip();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("An OpenRouter model ID is required.");
        }
        if (cleaned.length() > 512 || cleaned.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "Enter a valid OpenRouter model ID without spaces (up to 512 characters).");
        }
        if (repository == null) {
            writeProperty(settingsFile, OPENROUTER_MODEL_KEY, cleaned);
        } else {
            repository.put(OPENROUTER_MODEL_KEY, cleaned);
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
