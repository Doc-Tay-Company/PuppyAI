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
        LocalDate planUntil,
        int breakMinutes,
        int maxSessionMinutes
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
        // Jackson supplies zero for fields absent from older persisted records.
        breakMinutes = breakMinutes <= 0 ? 10 : breakMinutes;
        maxSessionMinutes = maxSessionMinutes <= 0 ? 90 : maxSessionMinutes;
        if (breakMinutes > 60) {
            throw new IllegalArgumentException("Breaks cannot exceed 60 minutes");
        }
        if (maxSessionMinutes < 15 || maxSessionMinutes > 180) {
            throw new IllegalArgumentException("Session limit must be between 15 and 180 minutes");
        }
    }

    public FocusPreferences(LocalTime earliest, LocalTime latest,
                            LocalDate planFrom, LocalDate planUntil) {
        this(earliest, latest, planFrom, planUntil, 10, 90);
    }

    /** 3-parameter constructor for backward compatibility. */
    public FocusPreferences(LocalTime earliest, LocalTime latest, LocalDate planUntil) {
        this(earliest, latest, null, planUntil, 10, 90);
    }

    /** Preferences without an explicit plan end, keeping the default horizon. */
    public FocusPreferences(LocalTime earliest, LocalTime latest) {
        this(earliest, latest, null, null, 10, 90);
    }

    public FocusPreferences withPlanUntil(LocalDate date) {
        return new FocusPreferences(earliest, latest, planFrom, date,
                breakMinutes, maxSessionMinutes);
    }

    public FocusPreferences withPlanFrom(LocalDate date) {
        return new FocusPreferences(earliest, latest, date, planUntil,
                breakMinutes, maxSessionMinutes);
    }

    public FocusPreferences withPlanScope(LocalDate from, LocalDate until) {
        return new FocusPreferences(earliest, latest, from, until,
                breakMinutes, maxSessionMinutes);
    }

    public FocusPreferences withBreakMinutes(int minutes) {
        return new FocusPreferences(earliest, latest, planFrom, planUntil,
                minutes, maxSessionMinutes);
    }

    public FocusPreferences withMaxSessionMinutes(int minutes) {
        return new FocusPreferences(earliest, latest, planFrom, planUntil,
                breakMinutes, minutes);
    }

    public static FocusPreferences defaults() {
        return new FocusPreferences(LocalTime.of(8, 0), LocalTime.of(22, 0));
    }
}
