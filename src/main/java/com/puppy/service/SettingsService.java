package com.puppy.service;

import com.puppy.enums.AIMode;
import com.puppy.model.AppSettings;
import com.puppy.repository.SettingsRepository;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and writes user settings.
 *
 * <p>Precedence for always-on-top: an explicit system property or environment
 * variable ({@code puppy.alwaysOnTop} / {@code PUPPY_ALWAYS_ON_TOP}) wins over the
 * stored value, which wins over the default of {@code true}. The override exists so
 * the desktop behaviour can be changed without editing the database.</p>
 */
public final class SettingsService {

    static final String ALWAYS_ON_TOP_KEY = "alwaysOnTop";
    static final String AI_MODE_KEY = "aiMode";

    private final SettingsRepository settings;
    private final Optional<Boolean> alwaysOnTopOverride;

    public SettingsService(SettingsRepository settings, Optional<Boolean> alwaysOnTopOverride) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.alwaysOnTopOverride = Objects.requireNonNull(alwaysOnTopOverride, "alwaysOnTopOverride");
    }

    /** Stored settings, with defaults for anything not saved yet. */
    public AppSettings load() {
        boolean alwaysOnTop = alwaysOnTopOverride
                .orElseGet(() -> storedBoolean(ALWAYS_ON_TOP_KEY).orElse(AppSettings.defaults().alwaysOnTop()));
        AIMode aiMode = storedAiMode().orElse(AppSettings.defaults().aiMode());
        return new AppSettings(alwaysOnTop, aiMode);
    }

    /** The always-on-top value to apply to the stage. */
    public boolean effectiveAlwaysOnTop() {
        return load().alwaysOnTop();
    }

    public void setAlwaysOnTop(boolean value) {
        settings.put(ALWAYS_ON_TOP_KEY, Boolean.toString(value));
    }

    /** Stored now so milestone 4 can route requests; the settings UI follows later. */
    public void setAiMode(AIMode mode) {
        Objects.requireNonNull(mode, "mode");
        settings.put(AI_MODE_KEY, mode.name());
    }

    private Optional<Boolean> storedBoolean(String key) {
        return settings.get(key)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> value.equals("true") || value.equals("false"))
                .map(Boolean::parseBoolean);
    }

    private Optional<AIMode> storedAiMode() {
        return settings.get(AI_MODE_KEY).flatMap(value -> {
            try {
                return Optional.of(AIMode.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        });
    }
}
