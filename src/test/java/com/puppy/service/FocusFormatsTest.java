package com.puppy.service;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.FocusPreferences;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusFormatsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    @Test
    void parsesCommonClockShapes() {
        assertEquals(LocalTime.of(18, 0), FocusFormats.parseTime("18:00"));
        assertEquals(LocalTime.of(6, 30), FocusFormats.parseTime("6h30"));
        assertEquals(LocalTime.of(18, 30), FocusFormats.parseTime("6h30 chieu"));
        assertEquals(LocalTime.of(13, 0), FocusFormats.parseTime("1 trua"));
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
    void parsesFreeTimeWindowsFromTypedFocusDescriptions() {
        assertEquals(new FocusPreferences(LocalTime.of(18, 0), LocalTime.of(21, 30)),
                FocusFormats.parseFreeTimeWindow(
                        "Mình rảnh 18:00-21:30, môn Toán quan trọng").orElseThrow());
        assertEquals(new FocusPreferences(LocalTime.of(7, 0), LocalTime.of(9, 0)),
                FocusFormats.parseFreeTimeWindow("từ 7h sáng đến 9h sáng").orElseThrow());
        assertEquals(new FocusPreferences(LocalTime.of(19, 0), LocalTime.of(21, 0)),
                FocusFormats.parseFreeTimeWindow("7h tối đến 9h tối").orElseThrow());
        assertEquals(new FocusPreferences(LocalTime.of(19, 0), LocalTime.of(21, 0)),
                FocusFormats.parseFreeTimeWindow("7h tối - 9h tối").orElseThrow());
        assertEquals(new FocusPreferences(LocalTime.of(19, 0), LocalTime.of(21, 0)),
                FocusFormats.parseFreeTimeWindow("từ 7h tối đến 9h").orElseThrow());
        assertEquals(new FocusPreferences(LocalTime.of(19, 0), LocalTime.of(21, 0)),
                FocusFormats.parseFreeTimeWindow("7-9pm").orElseThrow());
        assertEquals(new FocusPreferences(LocalTime.of(19, 0), LocalTime.of(21, 0)),
                FocusFormats.parseFreeTimeWindow("7pm to 9pm").orElseThrow());
        assertEquals(new FocusPreferences(LocalTime.of(18, 0), LocalTime.of(20, 0)),
                FocusFormats.parseFreeTimeWindow(
                        "mình rảnh từ 6 giờ chiều tới 8 giờ tối").orElseThrow());
        assertEquals(FocusPreferences.defaults(),
                FocusFormats.parseFreeTimeWindow("anytime").orElseThrow());
        assertEquals(FocusPreferences.defaults(),
                FocusFormats.parseFreeTimeWindow("khi nào cũng được").orElseThrow());
    }

    @Test
    void rejectsSingleTimesDatesAndOtherNonWindows() {
        assertTrue(FocusFormats.parseFreeTimeWindow("mình rảnh buổi tối").isEmpty());
        assertTrue(FocusFormats.parseFreeTimeWindow("8:00").isEmpty());
        assertTrue(FocusFormats.parseFreeTimeWindow("deadline 2026-11-20").isEmpty());
        assertTrue(FocusFormats.parseFreeTimeWindow("hạn 11-20-2026").isEmpty());
        assertTrue(FocusFormats.parseFreeTimeWindow("không rảnh cả ngày").isEmpty());
        assertTrue(FocusFormats.parseFreeTimeWindow("").isEmpty());
    }

    @Test
    void infersDeepFocusFromDifficultyImportanceAndConfidence() {
        assertEquals(FocusType.DEEP,
                FocusFormats.parseType("môn này rất quan trọng", FocusType.QUICK));
        assertEquals(FocusType.DEEP,
                FocusFormats.parseType("cần nhiều thời gian hơn", FocusType.QUICK));
        assertEquals(FocusType.DEEP,
                FocusFormats.parseType("mình chưa nắm chắc kiến thức", FocusType.QUICK));
        assertEquals(FocusType.DEEP,
                FocusFormats.parseType("this is difficult", FocusType.QUICK));
        assertEquals(FocusType.DEEP,
                FocusFormats.parseType("môn này khó", FocusType.QUICK));
        assertEquals(FocusType.QUICK,
                FocusFormats.parseType("khóa học nhẹ", FocusType.QUICK));
        assertEquals(FocusType.QUICK,
                FocusFormats.parseType("review lecture notes", FocusType.DEEP));
    }

    @Test
    void formatsTimesAndDatesForThePanel() {
        assertEquals("18:05", FocusFormats.formatTime(LocalTime.of(18, 5)));
        assertEquals("Mon 16 Nov", FocusFormats.formatDate(TODAY));
    }
}
