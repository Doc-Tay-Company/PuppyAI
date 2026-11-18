package com.puppy.service;

import com.puppy.config.AppConfig;
import com.puppy.repository.SettingsRepository;

/** Cached preferences; writes complete before their value changes. */
public final class AppSettingsService {
    private final SettingsRepository repository;
    private volatile boolean alwaysOnTop;

    public AppSettingsService(AppConfig config, SettingsRepository repository) {
        this.repository = repository;
        String saved = repository.get("alwaysOnTop").orElse(Boolean.toString(config.alwaysOnTop()));
        if (!saved.equals("true") && !saved.equals("false")) {
            throw new IllegalStateException("Invalid saved always-on-top setting; repair local storage.");
        }
        alwaysOnTop = AppConfig.hasAlwaysOnTopOverride() ? config.alwaysOnTop() : Boolean.parseBoolean(saved);
    }

    public boolean alwaysOnTop() {
        return alwaysOnTop;
    }

    public synchronized void setAlwaysOnTop(boolean value) {
        repository.put("alwaysOnTop", Boolean.toString(value));
        alwaysOnTop = value;
    }
}
