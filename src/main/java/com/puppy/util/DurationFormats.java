package com.puppy.util;

/**
 * Formats short processing times for the chat transcript.
 *
 * <p>The display switches units as the task grows: seconds below one minute,
 * minutes and seconds below one hour, then hours and minutes. This keeps a
 * quick reply readable as {@code 8s} and a long generation readable as
 * {@code 2m 15s} or {@code 1h 4m}.</p>
 */
public final class DurationFormats {

    private DurationFormats() {
    }

    /**
     * Formats elapsed milliseconds with the largest useful unit.
     *
     * @param millis elapsed time; values below one second are shown as {@code 1s}
     */
    public static String formatElapsed(long millis) {
        long totalSeconds = Math.max(1, Math.round(Math.max(0, millis) / 1000.0));
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }

        long totalMinutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (totalMinutes < 60) {
            return seconds == 0
                    ? totalMinutes + "m"
                    : totalMinutes + "m " + seconds + "s";
        }

        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
    }
}
