package com.puppy.repository.sqlite;

import com.puppy.persistence.Database;
import com.puppy.repository.SettingsRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite-backed {@link SettingsRepository}: one row per setting key.
 */
public final class SQLiteSettingsRepository implements SettingsRepository {

    private final Database database;

    public SQLiteSettingsRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM settings WHERE key = ?")) {
                statement.setString(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO settings (key, value) VALUES (?, ?) "
                            + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                statement.setString(1, key);
                statement.setString(2, value);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Map<String, String> getAll() {
        return database.query(connection -> {
            Map<String, String> settings = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT key, value FROM settings ORDER BY key");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    settings.put(result.getString(1), result.getString(2));
                }
            }
            return Collections.unmodifiableMap(settings);
        });
    }
}
