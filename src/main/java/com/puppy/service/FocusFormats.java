package com.puppy.service;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.FocusPreferences;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tolerant parsing helpers shared by the model-output reader and the Focus
 * panel. Files and local models mix {@code 8:30}, {@code 8h30}, {@code 20/11}
 * and Vietnamese weekday names, so all user-facing input goes through here.
 */
public final class FocusFormats {

    private static final Pattern CLOCK = Pattern.compile(
            "^(\\d{1,2})\\s*(?::|h|gio|g|[,.])\\s*(\\d{2})\\s*(am|pm|sa|ch|chieu|sang|trua|toi)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOUR_ONLY = Pattern.compile(
            "^(\\d{1,2})\\s*(?:h|gio|g)?\\s*(am|pm|sa|ch|chieu|sang|trua|toi)?$",
            Pattern.CASE_INSENSITIVE);
    private static final String TIME_TOKEN =
            "\\d{1,2}(?:\\s*(?::|h|gio|g|[,.])\\s*(?:\\d{2})?)?"
                    + "\\s*(?:am|pm|sa|ch|chieu|sang|trua|toi)?";
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?<![\\d-])(" + TIME_TOKEN + ")\\s*(?:-|–|—|den|toi|to)\\s*("
                    + TIME_TOKEN + ")(?!\\s*-\\s*\\d)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_SUFFIX = Pattern.compile(
            "(?:am|pm|sa|ch|chieu|sang|trua|toi)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AFTERNOON_SUFFIX = Pattern.compile(
            "(pm|chieu|trua|toi)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEEP_WORD = Pattern.compile("\\b(?:kho|hard|difficult)\\b");
    private static final Pattern HOURS_DURATION = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)(?:hours|hour|hrs|hr|h|gio|g)(\\d{1,2})?"
                    + "(?:minutes|minute|mins|min|phut|p|m)?$");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+(?:\\.\\d+)?)");
    private static final String DATE_TOKEN =
            "(\\d{4}\\s*[-/]\\s*\\d{1,2}\\s*[-/]\\s*\\d{1,2}"
                    + "|\\d{1,2}\\s*[-/]\\s*\\d{1,2}\\s*[-/]\\s*\\d{2,4}"
                    + "|\\d{1,2}\\s*[-/]\\s*\\d{1,2}"
                    + "|\\d{1,2}\\s+thang\\s+\\d{1,2}(?:\\s+nam\\s+\\d{4})?)";
    private static final Pattern PLAN_END_DATE = Pattern.compile(
            "(?:deadline|due(?: date)?|han(?:\\s+(?:chot|nop|cuoi))?|ngay hen|appointment"
                    + "|hen(?: gap)?|truoc(?: ngay)?|by|before|until|den(?: ngay)?"
                    + "|toi(?: ngay)?|vao(?: ngay)?|ngay)\\s*[:,\\-]?\\s*" + DATE_TOKEN,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAN_WEEKS = Pattern.compile(
            "(?<!thu )(?<!moi )(?<!hang )(\\d{1,2})\\s*(?:tuan|weeks?|week)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAN_MONTHS = Pattern.compile(
            "(?<!thu )(?<!moi )(?<!hang )(\\d{1,2})\\s*(?:thang|months?|month)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAN_DAYS = Pattern.compile(
            "(?<!thu )(?<!moi )(?<!hang )(\\d{1,3})\\s*(?:ngay|days?|day)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_PLAN_DAYS = 365;
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
                    || normalized.equals("chieu") || normalized.equals("trua")
                    || normalized.equals("toi");
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

    /**
     * Finds a free-time window in a typed sentence, for example
     * {@code 18:00-21:30}, {@code 7h-9h}, {@code 7h tối đến 9h tối} or
     * {@code 7pm to 9pm}. Only ranges are accepted; a single time such as
     * "after 8pm" returns empty so Chat can ask for the end of the window.
     *
     * @return the parsed window, or empty when the text has no usable range
     */
    public static Optional<FocusPreferences> parseFreeTimeWindow(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String text = normalize(value).replace('\u00a0', ' ').replaceAll("\\s+", " ");
        if (isAnyTime(text)) {
            return Optional.of(FocusPreferences.defaults());
        }

        Matcher matcher = TIME_RANGE.matcher(text);
        while (matcher.find()) {
            String startText = matcher.group(1).strip();
            String endText = matcher.group(2).strip();
            try {
                LocalTime start = parseTime(startText);
                LocalTime end = parseTime(endText);
                if (AFTERNOON_SUFFIX.matcher(endText).find()
                        && !TIME_SUFFIX.matcher(startText).find()
                        && start.isBefore(LocalTime.NOON)) {
                    LocalTime afternoonStart = start.plusHours(12);
                    if (afternoonStart.isBefore(end)) {
                        start = afternoonStart;
                    }
                }
                if (AFTERNOON_SUFFIX.matcher(startText).find()
                        && !TIME_SUFFIX.matcher(endText).find()
                        && end.isBefore(LocalTime.NOON)) {
                    LocalTime eveningEnd = end.plusHours(12);
                    if (start.isBefore(eveningEnd)) {
                        end = eveningEnd;
                    }
                }
                if (start.isBefore(end)) {
                    return Optional.of(new FocusPreferences(start, end));
                }
            } catch (IllegalArgumentException ignored) {
                // Try the next range in the sentence.
            }
        }
        return Optional.empty();
    }

    private static boolean isAnyTime(String normalized) {
        if (normalized.startsWith("khong") || normalized.contains("khong ranh")) {
            return false;
        }
        return normalized.contains("anytime")
                || normalized.contains("any time")
                || normalized.contains("bat cu luc nao")
                || normalized.contains("khi nao cung duoc")
                || normalized.contains("ca ngay");
    }

    /**
     * Finds the last day a plan should cover when the user states it in the
     * request, for example {@code trong 3 ngày}, {@code for 10 days},
     * {@code 2 tuần}, {@code 1 tháng}, {@code deadline 20/11} or
     * {@code before 2026-11-20}.
     *
     * <p>Durations are inclusive: "3 ngày" means today plus the next two days.
     * Dates without a year use the next non-past occurrence. The result is
     * capped at one year so a typo cannot create an enormous plan. An empty
     * result means the caller should use the default horizon (one week, or the
     * latest task deadline).</p>
     */
    public static Optional<LocalDate> parsePlanEnd(String value, LocalDate today) {
        Objects.requireNonNull(today, "today");
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String text = normalize(value).replace('\u00a0', ' ').replaceAll("\\s+", " ");

        Matcher dates = PLAN_END_DATE.matcher(text);
        if (dates.find()) {
            LocalDate parsed = parsePlanDate(dates.group(1), today);
            if (parsed != null) {
                return Optional.of(capPlanEnd(parsed, today));
            }
        }

        if (text.contains("hom nay") || text.contains("today")) {
            return Optional.of(today);
        }
        if (text.contains("ngay mai") || text.contains("tomorrow")) {
            return Optional.of(today.plusDays(1));
        }

        Matcher weeks = PLAN_WEEKS.matcher(text);
        if (weeks.find()) {
            return planDaysEnd(today, parseCount(weeks.group(1)) * 7L);
        }
        Matcher months = PLAN_MONTHS.matcher(text);
        if (months.find()) {
            long count = parseCount(months.group(1));
            if (count > 0) {
                LocalDate end = today.plusMonths(Math.min(count, 12)).minusDays(1);
                return Optional.of(capPlanEnd(end, today));
            }
        }
        Matcher days = PLAN_DAYS.matcher(text);
        if (days.find()) {
            return planDaysEnd(today, parseCount(days.group(1)));
        }
        return Optional.empty();
    }

    private static Optional<LocalDate> planDaysEnd(LocalDate today, long days) {
        if (days <= 0) {
            return Optional.empty();
        }
        long capped = Math.min(days, MAX_PLAN_DAYS);
        return Optional.of(today.plusDays(capped - 1));
    }

    private static LocalDate capPlanEnd(LocalDate date, LocalDate today) {
        LocalDate earliest = date.isBefore(today) ? today : date;
        LocalDate latest = today.plusDays(MAX_PLAN_DAYS - 1L);
        return earliest.isAfter(latest) ? latest : earliest;
    }

    private static long parseCount(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses one date token from {@link #PLAN_END_DATE} in normalized text. */
    private static LocalDate parsePlanDate(String token, LocalDate today) {
        String cleaned = token == null ? "" : token.strip();
        Matcher vietnamese = Pattern.compile(
                "(\\d{1,2})\\s+thang\\s+(\\d{1,2})(?:\\s+nam\\s+(\\d{4}))?")
                .matcher(cleaned);
        if (vietnamese.matches()) {
            Integer year = vietnamese.group(3) == null
                    ? null : Integer.valueOf(vietnamese.group(3));
            return planDate(Integer.parseInt(vietnamese.group(1)),
                    Integer.parseInt(vietnamese.group(2)), year, today);
        }

        Matcher numeric = Pattern.compile(
                "(\\d{1,4})\\s*[-/]\\s*(\\d{1,2})(?:\\s*[-/]\\s*(\\d{2,4}))?")
                .matcher(cleaned);
        if (!numeric.matches()) {
            return null;
        }
        int first = Integer.parseInt(numeric.group(1));
        int second = Integer.parseInt(numeric.group(2));
        String third = numeric.group(3);
        if (first >= 1000) {
            if (third == null) {
                return null;
            }
            return planDate(Integer.parseInt(third), second, first, today);
        }
        Integer year = third == null ? null : normalizeYear(Integer.parseInt(third));
        return planDate(first, second, year, today);
    }

    private static Integer normalizeYear(int year) {
        return year < 100 ? 2000 + year : year;
    }

    private static LocalDate planDate(int day, int month, Integer year, LocalDate today) {
        try {
            int resolvedYear = year == null ? today.getYear() : year;
            LocalDate date = LocalDate.of(resolvedYear, month, day);
            if (year == null && date.isBefore(today)) {
                date = date.plusYears(1);
            }
            return date;
        } catch (RuntimeException e) {
            return null;
        }
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
                || key.contains("lab") || key.contains("essay") || key.contains("important")
                || key.contains("quan trong") || key.contains("more time")
                || key.contains("nhieu thoi gian") || key.contains("difficult")
                || key.contains("hard") || key.contains("kho hieu") || key.contains("kho lam")
                || key.contains("not confident") || key.contains("chua nam chac")
                || key.contains("chua tu tin") || key.contains("khong tu tin")
                || key.contains("not mastered") || key.contains("chua hieu")
                || key.contains("chua vung") || key.contains("mat goc")
                || DEEP_WORD.matcher(key).find()) {
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
