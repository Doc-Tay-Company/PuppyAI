package com.puppy.model;

import com.puppy.enums.AIMode;

import java.util.Objects;

/**
 * User settings that outlive a single run of the application.
 *
 * @param alwaysOnTop keep the mascot above other windows
 * @param aiMode      preferred response source; stored now, routed in milestone 4
 */
public record AppSettings(boolean alwaysOnTop, AIMode aiMode) {

    public AppSettings {
        aiMode = Objects.requireNonNull(aiMode, "aiMode");
    }

    /** Settings used before the user has saved anything. */
    public static AppSettings defaults() {
        return new AppSettings(true, AIMode.AUTO);
    }
}
