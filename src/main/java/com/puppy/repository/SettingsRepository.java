package com.puppy.repository;

import com.puppy.database.Database;
import java.util.Optional;

public final class SettingsRepository {
    private final Database database;

    public SettingsRepository(Database database) {
        this.database = database;
    }

    public Optional<String> get(String key) {
        return database.call(connection -> {
            try (var statement = connection.prepareStatement("SELECT value FROM settings WHERE key=?")) {
                statement.setString(1, key);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
                }
            }
        });
    }

    public void put(String key, String value) {
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                statement.setString(1, key);
                statement.setString(2, value);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void importLegacyModel(String model) {
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement("INSERT OR IGNORE INTO settings(key,value) VALUES('selectedModel',?)")) {
                if (model != null && !model.isBlank()) {
                    statement.setString(1, model.strip());
                    statement.executeUpdate();
                }
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO settings(key,value) VALUES('legacyModelImported','true')");
            }
            return null;
        });
    }
}
