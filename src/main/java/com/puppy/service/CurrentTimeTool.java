package com.puppy.service;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Objects;

/** Read-only application tool, sampled when a request actually executes. */
public final class CurrentTimeTool {
    private final Clock clock;

    public CurrentTimeTool(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ZonedDateTime execute() {
        return ZonedDateTime.now(clock);
    }

    public static String context(ZonedDateTime now) {
        return """
                Application tool result: get_current_time
                Current date and time: %s
                Weekday: %s
                Timezone: %s
                UTC offset: %s
                Source: this computer's clock, sampled for this request (not an internet time service).
                Use this result for current-time/date questions and relative dates, not old chat timestamps.
                Format the answer in the user's language and requested 12-hour or 24-hour format.
                When no format is requested, use HH:mm and identify the timezone. Do not invent a time.
                """.formatted(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now),
                now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                now.getZone().getId(), now.getOffset().getId());
    }
}
