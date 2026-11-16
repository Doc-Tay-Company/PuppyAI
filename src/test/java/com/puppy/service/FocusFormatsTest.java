package com.puppy.service;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FocusFormatsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    @Test
    void parsesCommonClockShapes() {
        assertEquals(LocalTime.of(18, 0), FocusFormats.parseTime("18:00"));
        assertEquals(LocalTime.of(6, 30), FocusFormats.parseTime("6h30"));
        assertEquals(LocalTime.of(18, 30), FocusFormats.parseTime("6h30 chieu"));
        assertEquals(LocalTime.of(20, 0), FocusFormats.parseTime("8:00 pm"));
        assertEquals(LocalTime.of(0, 0), FocusFormats.parseTime("12:00 am"));
        assertEquals(LocalTime.of(7, 0), FocusFormats.parseTime("7"));
        assertThrows(IllegalArgumentException.class, () -> FocusFormats.parseTime("soon"));
    }

    @Test
    void parsesDatesDaysTypesAndPrioritiesFromEnglishAndVietnamese() {
        assertEquals(LocalDate.of(2026, 11, 20), FocusFormats.parseDate("2026-11-20", TODAY));
        assertEquals(LocalDate.of(2026, 11, 20), FocusFormats.parseDate("20/11/2026", TODAY));
        assertEquals(TODAY.plusDays(1), FocusFormats.parseDate("ngày mai", TODAY));

        assertEquals(DayOfWeek.MONDAY, FocusFormats.parseDay("Thứ Hai"));
        assertEquals(DayOfWeek.SATURDAY, FocusFormats.parseDay("Thứ Bảy"));
        assertEquals(DayOfWeek.SUNDAY, FocusFormats.parseDay("CN"));

        assertEquals(FocusType.DEEP, FocusFormats.parseType("assignment", FocusType.QUICK));
        assertEquals(FocusType.QUICK, FocusFormats.parseType("lecture review", FocusType.DEEP));

        assertEquals(Priority.HIGH, FocusFormats.parsePriority("cao", Priority.LOW));
        assertEquals(Priority.MEDIUM,
                FocusFormats.parsePriority("trung bình", Priority.HIGH));
        assertEquals(Priority.LOW, FocusFormats.parsePriority("thấp", Priority.HIGH));
    }

    @Test
    void parsesDurationTextWithoutMistakingVietnameseWordsForHours() {
        assertEquals(90, FocusFormats.parseMinutes("90"));
        assertEquals(90, FocusFormats.parseMinutes("1h30"));
        assertEquals(90, FocusFormats.parseMinutes("1.5h"));
        assertEquals(45, FocusFormats.parseMinutes("45 phút"));
        assertEquals(45, FocusFormats.parseMinutes("45 phut"));
        assertEquals(90, FocusFormats.parseMinutes("1 giờ 30 phút"));
        assertEquals(20, FocusFormats.parseMinutes("20 min"));
        assertEquals(60, FocusFormats.parseMinutes("1 hour"));
        assertEquals(90, FocusFormats.parseMinutes("1 hr 30"));
        assertThrows(IllegalArgumentException.class, () -> FocusFormats.parseMinutes("later"));
    }

    @Test
    void formatsTimesAndDatesForThePanel() {
        assertEquals("18:05", FocusFormats.formatTime(LocalTime.of(18, 5)));
        assertEquals("Mon 16 Nov", FocusFormats.formatDate(TODAY));
    }
}
