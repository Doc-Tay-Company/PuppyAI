package com.puppy.repository;

import java.util.Map;
import java.util.Optional;

/**
 * Small key/value store for user settings.
 */
public interface SettingsRepository {

    Optional<String> get(String key);

    /** Inserts the key or replaces its value. */
    void put(String key, String value);

    Map<String, String> getAll();
}
