package com.puppy.service;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.FocusExtraction;
import com.puppy.model.StudyTask;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusJsonParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    private final FocusJsonParser parser = new FocusJsonParser();

    @Test
    void readsClassesAndTasksFromStrictJson() throws Exception {
        String reply = """
                {
                  "classes": [
                    {"subject": "Math", "day": "MONDAY", "start": "08:00", "end": "09:30"}
                  ],
                  "tasks": [
                    {"subject": "Math", "title": "Review chapter 3", "type": "QUICK",
                     "estimatedMinutes": 60, "deadline": "2026-11-20", "priority": "HIGH"}
                  ]
                }
                """;

        FocusExtraction extraction = parser.parse(reply, TODAY);

        assertEquals(1, extraction.input().classes().size());
        ClassSlot classSlot = extraction.input().classes().get(0);
        assertEquals("Math", classSlot.subject());
        assertEquals(DayOfWeek.MONDAY, classSlot.day());
        assertEquals(LocalTime.of(8, 0), classSlot.start());
        assertEquals(LocalTime.of(9, 30), classSlot.end());

        assertEquals(1, extraction.input().tasks().size());
        StudyTask task = extraction.input().tasks().get(0);
        assertEquals("Review chapter 3", task.title());
        assertEquals(FocusType.QUICK, task.type());
        assertEquals(60, task.estimatedMinutes());
        assertEquals(LocalDate.of(2026, 11, 20), task.deadline());
        assertEquals(Priority.HIGH, task.priority());
    }

    @Test
    void acceptsJsonInsideAFenceOrAfterAComment() throws Exception {
        String fenced = """
                ```json
                {"tasks": [{"subject": "Physics", "title": "Read notes",
                  "type": "QUICK", "estimatedMinutes": 25}]}
                ```
                """;
        String commented = """
                Here is the extracted plan:
                {"tasks": [{"subject": "Physics", "title": "Read notes",
                  "type": "QUICK", "estimatedMinutes": 25}]}
                I hope that helps!
                """;

        assertEquals("Physics", parser.parse(fenced, TODAY)
                .input().tasks().get(0).subject());
        assertEquals("Physics", parser.parse(commented, TODAY)
                .input().tasks().get(0).subject());
    }

    @Test
    void readsVietnameseWeekdaysTimesDatesAndPriorities() throws Exception {
        String reply = """
                {
                  "classes": [
                    {"subject": "Toán cao cấp", "day": "Thứ Hai",
                     "start": "6h30 chieu", "end": "8:00 pm"}
                  ],
                  "tasks": [
                    {"subject": "Toán cao cấp", "title": "Ôn bài giảng",
                     "type": "QUICK", "estimatedMinutes": "45 phút",
                     "deadline": "20/11/2026", "priority": "cao"}
                  ]
                }
                """;

        FocusExtraction extraction = parser.parse(reply, TODAY);

        ClassSlot classSlot = extraction.input().classes().get(0);
        assertEquals(DayOfWeek.MONDAY, classSlot.day());
        assertEquals(LocalTime.of(18, 30), classSlot.start());
        assertEquals(LocalTime.of(20, 0), classSlot.end());

        StudyTask task = extraction.input().tasks().get(0);
        assertEquals(45, task.estimatedMinutes());
        assertEquals(LocalDate.of(2026, 11, 20), task.deadline());
        assertEquals(Priority.HIGH, task.priority());
    }

    @Test
    void skipsInvalidRowsWithANoteInsteadOfRejectingTheFile() throws Exception {
        String reply = """
                {
                  "classes": [
                    {"subject": "Math", "day": "MONDAY", "start": "08:00"}
                  ],
                  "tasks": [
                    {"title": "No subject here"},
                    {"subject": "Math", "title": "Read notes"}
                  ]
                }
                """;

        FocusExtraction extraction = parser.parse(reply, TODAY);

        assertTrue(extraction.input().classes().isEmpty());
        assertEquals(1, extraction.input().tasks().size());
        assertEquals(2, extraction.notes().size(), extraction.notes().toString());
        assertTrue(extraction.notes().stream()
                .anyMatch(note -> note.contains("Skipped class 1")));
        assertTrue(extraction.notes().stream()
                .anyMatch(note -> note.contains("Skipped task 1")));
    }

    @Test
    void createsAReviewTaskWhenTheFileOnlyListsSubjects() throws Exception {
        String reply = """
                {"subjects": ["Math", "Physics"]}
                """;

        FocusExtraction extraction = parser.parse(reply, TODAY);

        assertEquals(2, extraction.input().tasks().size());
        StudyTask task = extraction.input().tasks().get(0);
        assertEquals("Math", task.subject());
        assertEquals("Review class notes", task.title());
        assertEquals(FocusType.QUICK, task.type());
        assertEquals(TODAY.plusDays(FocusPlanner.DEFAULT_HORIZON_DAYS - 1L), task.deadline());
        assertTrue(extraction.notes().stream()
                .anyMatch(note -> note.contains("No tasks were listed")));
    }

    @Test
    void alwaysAddsAReviewTaskForEverySubjectEvenWhenOtherTasksExist() throws Exception {
        String reply = """
                {
                  "tasks": [
                    {"subject": "Math", "title": "Problem set", "type": "QUICK",
                     "estimatedMinutes": 25}
                  ],
                  "subjects": ["Math", "Physics"]
                }
                """;

        FocusExtraction extraction = parser.parse(reply, TODAY);

        assertEquals(3, extraction.input().tasks().size(),
                "The explicit task and a review task for both subjects should be present");
        assertTrue(extraction.input().tasks().stream().anyMatch(task ->
                        task.subject().equals("Math") && task.title().equals("Problem set")),
                "The explicit task must be kept");
        assertTrue(extraction.input().tasks().stream().anyMatch(task ->
                        task.subject().equals("Physics")
                                && task.title().equals("Review class notes")),
                "Physics still needs a review task");
    }

    @Test
    void readsMoreTimeSubjectsAsAnAdjustmentNotAnExtraTask() throws Exception {
        String reply = """
                {
                  "subjects": ["Math", "Physics"],
                  "moreTimeSubjects": ["Math"]
                }
                """;

        FocusExtraction extraction = parser.parse(reply, TODAY);

        assertEquals(List.of("Math"), extraction.input().moreTimeSubjects());
        assertEquals(2, extraction.input().tasks().size(),
                "Math should not get an extra task just for needing more time");
        assertTrue(extraction.input().tasks().stream()
                .allMatch(task -> task.title().equals("Review class notes")));
    }

    @Test
    void readsMoreTimeFlagsAndKeepsSubjectsMissingFromTheList() throws Exception {
        String reply = """
                {
                  "subjects": [
                    {"subject": "Math", "moreTime": true},
                    "Physics"
                  ],
                  "moreTimeSubjects": [{"subject": "Chemistry"}]
                }
                """;

        FocusExtraction extraction = parser.parse(reply, TODAY);

        assertTrue(extraction.input().moreTimeSubjects().containsAll(
                        List.of("Math", "Chemistry")),
                "Flags and the dedicated list should be merged: "
                        + extraction.input().moreTimeSubjects());
        assertEquals(3, extraction.input().tasks().size(),
                "Every subject, including the extra-time one, gets a review task");
        assertTrue(extraction.input().tasks().stream()
                .anyMatch(task -> task.subject().equals("Chemistry")));
    }

    @Test
    void marksAnImportantSubjectOnlyDescriptionAsDeep() throws Exception {
        String reply = """
                {"subjects": [
                  {"subject": "Math", "type": "DEEP"},
                  "Physics"
                ]}
                """;

        FocusExtraction extraction = parser.parse(reply, TODAY);

        assertEquals(2, extraction.input().tasks().size());
        StudyTask deep = extraction.input().tasks().get(0);
        StudyTask quick = extraction.input().tasks().get(1);
        assertEquals(FocusType.DEEP, deep.type());
        assertEquals(60, deep.estimatedMinutes());
        assertEquals(FocusType.QUICK, quick.type());
        assertEquals(50, quick.estimatedMinutes());
    }

    @Test
    void rejectsEmptyNonJsonAndEmptyPlans() {
        assertThrows(FocusPlanningException.class, () -> parser.parse("", TODAY));
        assertThrows(FocusPlanningException.class,
                () -> parser.parse("I couldn't read the timetable.", TODAY));
        assertThrows(FocusPlanningException.class,
                () -> parser.parse("{\"classes\": [], \"tasks\": []}", TODAY));
    }

    @Test
    void leavesMissingDeadlinesEmpty() throws Exception {
        String reply = """
                {"tasks": [{"subject": "Art", "title": "Sketch", "type": "QUICK",
                  "estimatedMinutes": 25}]}
                """;

        StudyTask task = parser.parse(reply, TODAY).input().tasks().get(0);

        assertNull(task.deadline());
        assertEquals(Priority.MEDIUM, task.priority());
        assertTrue(task.title().contains("Sketch"));
        assertFalse(task.estimatedMinutes() <= 0);
    }
}
