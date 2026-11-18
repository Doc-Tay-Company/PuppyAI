package com.puppy.service;

import com.puppy.config.AppConfig;
import com.puppy.repository.SettingsRepository;

/** Cached preferences; writes complete before their value changes. */
public final class AppSettingsService {
    public static final int MAX_INSTRUCTION_CHARACTERS = 4_000;
    private static final String INSTRUCTIONS_KEY = "additionalInstructions";
    private final SettingsRepository repository;
    private volatile boolean alwaysOnTop;
    private volatile String additionalInstructions;

    public AppSettingsService(AppConfig config, SettingsRepository repository) {
        this.repository = repository;
        String saved = repository.get("alwaysOnTop").orElse(Boolean.toString(config.alwaysOnTop()));
        if (!saved.equals("true") && !saved.equals("false")) {
            throw new IllegalStateException("Invalid saved always-on-top setting; repair local storage.");
        }
        alwaysOnTop = AppConfig.hasAlwaysOnTopOverride() ? config.alwaysOnTop() : Boolean.parseBoolean(saved);
        additionalInstructions = repository.get(INSTRUCTIONS_KEY).orElse("");
        validateAdditionalInstructions(additionalInstructions);
    }

    public boolean alwaysOnTop() {
        return alwaysOnTop;
    }

    public synchronized void setAlwaysOnTop(boolean value) {
        repository.put("alwaysOnTop", Boolean.toString(value));
        alwaysOnTop = value;
    }

    public String additionalInstructions() {
        return additionalInstructions;
    }

    public synchronized void setAdditionalInstructions(String value) {
        validateAdditionalInstructions(value);
        repository.put(INSTRUCTIONS_KEY, value);
        additionalInstructions = value;
    }

    public static void validateAdditionalInstructions(String value) {
        java.util.Objects.requireNonNull(value, "instructions");
        if (value.codePointCount(0, value.length()) > MAX_INSTRUCTION_CHARACTERS) {
            throw new IllegalArgumentException("Use no more than 4,000 characters for additional instructions.");
        }
    }
}
