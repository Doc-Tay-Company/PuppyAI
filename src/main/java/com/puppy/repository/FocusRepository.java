package com.puppy.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.puppy.database.Database;
import com.puppy.model.FocusState;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Stores a complete planner revision atomically through the serialized database path. */
public class FocusRepository {
    private final Database database;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public FocusRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public Optional<FocusState> load() {
        return database.transaction(connection -> {
            String payload;
            try (var statement = connection.prepareStatement(
                    "SELECT payload FROM focus_state WHERE singleton = 1");
                 var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                payload = rows.getString(1);
            }
            var snapshot = mapper.readTree(payload);
            boolean migrated = false;
            if (snapshot instanceof ObjectNode root) {
                // Retired planner controls have no counterpart in the current model.
                migrated = root.remove("preferencesConfigured") != null;
                migrated |= root.remove("planEdited") != null;
                if (root.get("preferences") instanceof ObjectNode preferences) {
                    for (String field : List.of("planFrom", "breakMinutes", "maxSessionMinutes")) {
                        migrated |= preferences.remove(field) != null;
                    }
                }
            }
            FocusState state = mapper.treeToValue(snapshot, FocusState.class);
            if (migrated) {
                try (var statement = connection.prepareStatement(
                        "UPDATE focus_state SET payload = ? WHERE singleton = 1")) {
                    statement.setString(1, mapper.writeValueAsString(snapshot));
                    statement.executeUpdate();
                }
            }
            return Optional.of(state);
        });
    }

    public void save(FocusState state) {
        database.transaction(connection -> {
            String payload = mapper.writeValueAsString(state);
            try (var statement = connection.prepareStatement(
                    "INSERT INTO focus_state(singleton, payload) VALUES(1, ?) "
                            + "ON CONFLICT(singleton) DO UPDATE SET payload = excluded.payload")) {
                statement.setString(1, payload);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
