package com.puppy.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.puppy.database.Database;
import com.puppy.model.FocusState;

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
        return database.call(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT payload FROM focus_state WHERE singleton = 1");
                 var rows = statement.executeQuery()) {
                return rows.next()
                        ? Optional.of(mapper.readValue(rows.getString(1), FocusState.class))
                        : Optional.empty();
            }
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
