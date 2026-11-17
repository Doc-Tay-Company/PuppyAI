package com.puppy.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationFormatsTest {

    @Test
    void showsSecondsForRepliesUnderAMinute() {
        assertEquals("1s", DurationFormats.formatElapsed(0));
        assertEquals("8s", DurationFormats.formatElapsed(8_200));
        assertEquals("59s", DurationFormats.formatElapsed(59_200));
    }

    @Test
    void switchesToMinutesAndSecondsAfterAMinute() {
        assertEquals("1m", DurationFormats.formatElapsed(59_700));
        assertEquals("1m 5s", DurationFormats.formatElapsed(65_000));
        assertEquals("2m 15s", DurationFormats.formatElapsed(135_000));
        assertEquals("59m 59s", DurationFormats.formatElapsed(3_599_000));
    }

    @Test
    void switchesToHoursAfterSixtyMinutes() {
        assertEquals("1h", DurationFormats.formatElapsed(3_600_000));
        assertEquals("1h 5m", DurationFormats.formatElapsed(3_900_000));
        assertEquals("2h 3m", DurationFormats.formatElapsed(7_380_000));
    }
}
