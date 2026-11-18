package com.puppy.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * The daily window in which Puppy may schedule self-study, plus an optional
 * start day and last day for the plan. School classes, meal breaks and other fixed
 * commitments are subtracted from the daily window.
 *
 * @param planFrom  first day the plan may use, or {@code null} to start today.
 * @param planUntil last day the plan may use, or {@code null} for the default
 *                  one-week horizon (or the latest task deadline). The
 *                  {@link com.puppy.service.FocusService} consumes this value
 *                  after building one plan so it does not affect later plans.
 */
public record FocusPreferences(
        LocalTime earliest,
        LocalTime latest,
        LocalDate planFrom,
        LocalDate planUntil
) {

    public FocusPreferences {
        Objects.requireNonNull(earliest, "earliest");
        Objects.requireNonNull(latest, "latest");
        if (!earliest.isBefore(latest)) {
            throw new IllegalArgumentException("Free time must end after it starts");
        }
        if (planFrom != null && planUntil != null && planFrom.isAfter(planUntil)) {
            throw new IllegalArgumentException("Plan start date must be before or on plan end date");
        }
    }

    /** 3-parameter constructor for backward compatibility. */
    public FocusPreferences(LocalTime earliest, LocalTime latest, LocalDate planUntil) {
        this(earliest, latest, null, planUntil);
    }

    /** Preferences without an explicit plan end, keeping the default horizon. */
    public FocusPreferences(LocalTime earliest, LocalTime latest) {
        this(earliest, latest, null, null);
    }

    public FocusPreferences withPlanUntil(LocalDate date) {
        return new FocusPreferences(earliest, latest, planFrom, date);
    }

    public FocusPreferences withPlanFrom(LocalDate date) {
        return new FocusPreferences(earliest, latest, date, planUntil);
    }

    public FocusPreferences withPlanScope(LocalDate from, LocalDate until) {
        return new FocusPreferences(earliest, latest, from, until);
    }

    public static FocusPreferences defaults() {
        return new FocusPreferences(LocalTime.of(8, 0), LocalTime.of(22, 0));
    }
}
