package com.puppy.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppy.model.TodoTask;

import java.util.List;
import java.util.Objects;

/** Stores the to-do collection as one atomic JSON revision in local settings. */
public final class TodoRepository {
    private static final String KEY = "todo.tasks.v1";
    private static final TypeReference<List<TodoTask>> TYPE = new TypeReference<>() { };

    private final SettingsRepository settings;
    private final ObjectMapper mapper = new ObjectMapper();

    public TodoRepository(SettingsRepository settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public List<TodoTask> load() {
        return settings.get(KEY).map(payload -> {
            try {
                return List.copyOf(mapper.readValue(payload, TYPE));
            } catch (Exception e) {
                throw new IllegalStateException("Saved to-do data is not readable.", e);
            }
        }).orElseGet(List::of);
    }

    public void save(List<TodoTask> tasks) {
        try {
            settings.put(KEY, mapper.writeValueAsString(tasks));
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the to-do list.", e);
        }
    }
}
