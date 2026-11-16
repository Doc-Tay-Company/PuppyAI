package com.puppy.model;

import java.time.LocalTime;
import java.util.Objects;

/**
 * The daily window in which Puppy may schedule self-study. School classes,
 * meal breaks and other fixed commitments are subtracted from this window.
 */
public record FocusPreferences(LocalTime earliest, LocalTime latest) {

    public FocusPreferences {
        Objects.requireNonNull(earliest, "earliest");
        Objects.requireNonNull(latest, "latest");
        if (!earliest.isBefore(latest)) {
            throw new IllegalArgumentException("Free time must end after it starts");
        }
    }

    public static FocusPreferences defaults() {
        return new FocusPreferences(LocalTime.of(8, 0), LocalTime.of(22, 0));
    }
}
