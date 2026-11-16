package com.puppy.service;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tolerant parsing helpers shared by the model-output reader and the Focus
 * panel. Files and local models mix {@code 8:30}, {@code 8h30}, {@code 20/11}
 * and Vietnamese weekday names, so all user-facing input goes through here.
 */
public final class FocusFormats {

    private static final Pattern CLOCK = Pattern.compile(
            "^(\\d{1,2})\\s*[:h.,]\\s*(\\d{2})\\s*(am|pm|sa|ch|chieu|sang)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOUR_ONLY = Pattern.compile(
            "^(\\d{1,2})\\s*(am|pm|sa|ch|chieu|sang)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURS_DURATION = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)(?:hours|hour|hrs|hr|h|gio|g)(\\d{1,2})?"
                    + "(?:minutes|minute|mins|min|phut|p|m)?$");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+(?:\\.\\d+)?)");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d/M/yyyy");
    private static final DateTimeFormatter DAY_MONTH_YEAR_DASHES =
            DateTimeFormatter.ofPattern("d-M-yyyy");
    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private FocusFormats() {
    }

    /** Parses a clock time, accepting {@code 18:00}, {@code 6h30} and 12-hour suffixes. */
    public static LocalTime parseTime(String value) {
        String cleaned = value == null ? "" : value.strip().replace('\u00a0', ' ');
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Enter a time such as 18:00");
        }

        int hour;
        int minute;
        String suffix;
        Matcher clock = CLOCK.matcher(cleaned);
        if (clock.matches()) {
            hour = Integer.parseInt(clock.group(1));
            minute = Integer.parseInt(clock.group(2));
            suffix = clock.group(3);
        } else {
            Matcher hourOnly = HOUR_ONLY.matcher(cleaned);
            if (!hourOnly.matches()) {
                throw new IllegalArgumentException("Enter a time such as 18:00");
            }
            hour = Integer.parseInt(hourOnly.group(1));
            minute = 0;
            suffix = hourOnly.group(2);
        }

        if (suffix != null) {
            String normalized = normalize(suffix);
            boolean afternoon = normalized.equals("pm") || normalized.equals("ch")
                    || normalized.equals("chieu");
            if (afternoon && hour < 12) {
                hour += 12;
            } else if (!afternoon && hour == 12) {
                hour = 0;
            }
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("Enter a time between 00:00 and 23:59");
        }
        return LocalTime.of(hour, minute);
    }

    /** Parses ISO dates plus the Vietnamese-style {@code 20/11/2026}. */
    public static LocalDate parseDate(String value, LocalDate today) {
        String cleaned = value == null ? "" : value.strip();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Enter a date such as 2026-11-20");
        }
        String normalized = normalize(cleaned);
        if (normalized.equals("today") || normalized.equals("hom nay")) {
            return today;
        }
        if (normalized.equals("tomorrow") || normalized.equals("ngay mai")) {
            return today.plusDays(1);
        }

        for (DateTimeFormatter formatter : new DateTimeFormatter[]{
                ISO_DATE, DAY_MONTH_YEAR, DAY_MONTH_YEAR_DASHES}) {
            try {
                return LocalDate.parse(cleaned, formatter);
            } catch (RuntimeException ignored) {
                // Try the next known shape.
            }
        }
        throw new IllegalArgumentException("Enter a date such as 2026-11-20");
    }

    /** Parses English and Vietnamese weekday names and abbreviations. */
    public static DayOfWeek parseDay(String value) {
        String key = normalize(value).replaceAll("\\s+", " ");
        return switch (key) {
            case "monday", "mon", "mo", "t2", "thu 2", "thu hai", "hai", "2" -> DayOfWeek.MONDAY;
            case "tuesday", "tue", "tues", "t3", "thu 3", "thu ba", "ba", "3" -> DayOfWeek.TUESDAY;
            case "wednesday", "wed", "t4", "thu 4", "thu tu", "tu", "4" -> DayOfWeek.WEDNESDAY;
            case "thursday", "thu", "thur", "thurs", "t5", "thu 5", "thu nam", "nam", "5" ->
                    DayOfWeek.THURSDAY;
            case "friday", "fri", "t6", "thu 6", "thu sau", "sau", "6" -> DayOfWeek.FRIDAY;
            case "saturday", "sat", "t7", "thu 7", "thu bay", "bay", "7" -> DayOfWeek.SATURDAY;
            case "sunday", "sun", "cn", "chu nhat", "nhat", "8" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("Unknown weekday: " + value);
        };
    }

    public static FocusType parseType(String value, FocusType fallback) {
        String key = normalize(value);
        if (key.isEmpty()) {
            return fallback;
        }
        if (key.contains("deep") || key.contains("assignment") || key.contains("coding")
                || key.contains("program") || key.contains("research") || key.contains("project")
                || key.contains("lab") || key.contains("essay")) {
            return FocusType.DEEP;
        }
        if (key.contains("quick") || key.contains("review") || key.contains("read")
                || key.contains("lecture") || key.contains("flashcard") || key.contains("note")) {
            return FocusType.QUICK;
        }
        return fallback;
    }

    public static Priority parsePriority(String value, Priority fallback) {
        String key = normalize(value);
        if (key.isEmpty()) {
            return fallback;
        }
        if (key.contains("high") || key.contains("cao") || key.contains("urgent")
                || key.contains("important") || key.equals("3") || key.equals("p1")) {
            return Priority.HIGH;
        }
        if (key.contains("low") || key.contains("thap") || key.contains("later")
                || key.equals("1") || key.equals("p3")) {
            return Priority.LOW;
        }
        if (key.contains("medium") || key.contains("normal") || key.contains("trung binh")
                || key.equals("2") || key.equals("p2")) {
            return Priority.MEDIUM;
        }
        return fallback;
    }

    /** Parses a duration such as {@code 90}, {@code 90 min}, {@code 1h30}, {@code 1.5h} or {@code 45 phút}. */
    public static int parseMinutes(String value) {
        String cleaned = normalize(value).replaceAll("\\s+", "").replace(',', '.');
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Enter a duration in minutes");
        }
        Matcher hours = HOURS_DURATION.matcher(cleaned);
        if (hours.matches()) {
            double hourValue = Double.parseDouble(hours.group(1));
            int extra = hours.group(2) == null || hours.group(2).isEmpty()
                    ? 0
                    : Integer.parseInt(hours.group(2));
            return (int) Math.round(hourValue * 60) + extra;
        }
        Matcher leading = LEADING_NUMBER.matcher(cleaned);
        if (leading.find()) {
            return (int) Math.round(Double.parseDouble(leading.group(1)));
        }
        throw new IllegalArgumentException("Enter a duration in minutes");
    }

    public static String formatTime(LocalTime time) {
        return DISPLAY_TIME.format(time);
    }

    public static String formatDate(LocalDate date) {
        return DISPLAY_DATE.format(date);
    }

    public static String formatDay(DayOfWeek day) {
        return day == null ? "" : day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /** Lower-cases and strips Vietnamese diacritics so comparisons stay simple. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .strip();
    }
}
