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
    void parsesExplicitPlanDurationsInDaysWeeksAndMonths() {
        assertEquals(TODAY.plusDays(2),
                FocusFormats.parsePlanEnd("trong 3 ngày", TODAY).orElseThrow(),
                "Durations are inclusive");
        assertEquals(TODAY.plusDays(9),
                FocusFormats.parsePlanEnd("for 10 days", TODAY).orElseThrow());
        assertEquals(TODAY.plusDays(13),
                FocusFormats.parsePlanEnd("2 tuần", TODAY).orElseThrow());
        assertEquals(TODAY.plusMonths(1).minusDays(1),
                FocusFormats.parsePlanEnd("1 tháng", TODAY).orElseThrow());
        assertEquals(TODAY.plusDays(364),
                FocusFormats.parsePlanEnd("in 400 days", TODAY).orElseThrow(),
                "A typo cannot create a plan longer than one year");
        assertTrue(FocusFormats.parsePlanEnd("không giới hạn", TODAY).isEmpty());
    }

    @Test
    void parsesExplicitDeadlineDatesForThePlan() {
        assertEquals(LocalDate.of(2026, 11, 20),
                FocusFormats.parsePlanEnd("deadline 20/11", TODAY).orElseThrow());
        assertEquals(LocalDate.of(2026, 12, 1),
                FocusFormats.parsePlanEnd("hạn chót 2026-12-01", TODAY).orElseThrow());
        assertEquals(LocalDate.of(2026, 12, 20),
                FocusFormats.parsePlanEnd("trước 20/12", TODAY).orElseThrow());
        assertEquals(LocalDate.of(2026, 11, 25),
                FocusFormats.parsePlanEnd("hạn nộp 25/11", TODAY).orElseThrow());
        assertEquals(LocalDate.of(2026, 11, 21),
                FocusFormats.parsePlanEnd("lịch hẹn 21/11", TODAY).orElseThrow());
        assertEquals(LocalDate.of(2027, 1, 10),
                FocusFormats.parsePlanEnd("deadline 10/1", TODAY).orElseThrow(),
                "A date without a year uses its next non-past occurrence");
        assertEquals(LocalDate.of(2026, 11, 20),
                FocusFormats.parsePlanEnd(
                        "deadline 20/11, mình rảnh 18:00-21:30", TODAY).orElseThrow(),
                "A date wins over the free-time window in the same sentence");
        assertEquals(TODAY, FocusFormats.parsePlanEnd("hôm nay", TODAY).orElseThrow());
        assertEquals(TODAY.plusDays(1),
                FocusFormats.parsePlanEnd("ngày mai", TODAY).orElseThrow());
        assertEquals(LocalDate.of(2026, 11, 25),
                FocusFormats.parsePlanEnd("kéo dài kế hoạch đến ngày 25", TODAY).orElseThrow());
    }

    @Test
    void ignoresWeekdaysTimesAndUnrelatedTextWhenParsingThePlanEnd() {
        assertTrue(FocusFormats.parsePlanEnd("mình rảnh thứ 2 tuần sau", TODAY).isEmpty(),
                "A weekday phrase is not a plan duration");
        assertTrue(FocusFormats.parsePlanEnd("18:00-21:30", TODAY).isEmpty());
        assertTrue(FocusFormats.parsePlanEnd("", TODAY).isEmpty());
        assertTrue(FocusFormats.parsePlanEnd(null, TODAY).isEmpty());
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

    @Test
    void parsesPlanDateRangeFromText() {
        // TODAY is 2026-11-16
        var range1 = FocusFormats.parsePlanDateRange("kế hoạch từ ngày 20 đến ngày 25", TODAY);
        assertTrue(range1.isPresent());
        assertEquals(LocalDate.of(2026, 11, 20), range1.get().from());
        assertEquals(LocalDate.of(2026, 11, 25), range1.get().until());

        var range2 = FocusFormats.parsePlanDateRange("từ 20/11 đến 25/11", TODAY);
        assertTrue(range2.isPresent());
        assertEquals(LocalDate.of(2026, 11, 20), range2.get().from());
        assertEquals(LocalDate.of(2026, 11, 25), range2.get().until());

        var range3 = FocusFormats.parsePlanDateRange("from 2026-11-20 to 2026-11-25", TODAY);
        assertTrue(range3.isPresent());
        assertEquals(LocalDate.of(2026, 11, 20), range3.get().from());
        assertEquals(LocalDate.of(2026, 11, 25), range3.get().until());
    }

    @Test
    void parsesSingleDayWithoutJumpingMonthUnlessExplicitlyRequested() {
        // TODAY is 2026-11-16
        // When user asks "đến ngày 25" -> should be 2026-11-25
        assertEquals(LocalDate.of(2026, 11, 25),
                FocusFormats.parsePlanEnd("kế hoạch đến ngày 25", TODAY).orElseThrow());

        // When user asks "ngày 10" (past day in November without "tháng sau") -> clamped to TODAY, not Dec 10!
        assertEquals(TODAY,
                FocusFormats.parsePlanEnd("kế hoạch ngày 10", TODAY).orElseThrow());

        // When user explicitly asks "đến ngày 25 tháng sau" -> jumps to Dec 25
        assertEquals(LocalDate.of(2026, 12, 25),
                FocusFormats.parsePlanEnd("đến ngày 25 tháng sau", TODAY).orElseThrow());
    }
}
