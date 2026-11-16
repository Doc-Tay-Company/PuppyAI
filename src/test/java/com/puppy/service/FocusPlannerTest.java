package com.puppy.service;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.FocusPlan;
import com.puppy.model.FocusPlannerInput;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.StudyTask;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusPlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16); // Monday
    private static final LocalDateTime NOW = TODAY.atTime(7, 0);

    private final FocusPlanner planner = new FocusPlanner();

    @Test
    void neverPlacesASessionOverAClass() {
        FocusPlannerInput input = new FocusPlannerInput(
                List.of(new ClassSlot("Math", DayOfWeek.MONDAY,
                        time(8, 0), time(9, 30))),
                List.of(task("Math", "Read chapter 2", FocusType.QUICK,
                        25, TODAY, Priority.MEDIUM)));

        FocusPlan plan = planner.plan(input, preferences(8, 0, 12, 0), NOW);

        assertEquals(1, plan.sessions().size(), "The task should be scheduled");
        FocusSession session = plan.sessions().get(0);
        assertEquals(time(9, 30), session.start(),
                "The free window starts when the class ends");
        assertFalse(session.start().isBefore(time(9, 30)));
        assertFalse(session.end().isAfter(time(12, 0)));
    }

    @Test
    void keepsEverySessionInsideFreeTimeWithTenMinuteBreaks() {
        FocusPlannerInput input = new FocusPlannerInput(
                List.of(),
                List.of(task("Math", "Practice problems", FocusType.QUICK,
                        80, null, Priority.MEDIUM)));

        FocusPlan plan = planner.plan(input, preferences(8, 0, 10, 0), NOW);

        assertEquals(4, plan.sessions().size(),
                "An 80-minute quick task should become four 20-minute blocks");
        Map<LocalDate, LocalTime> previousEnds = new HashMap<>();
        for (FocusSession session : plan.sessions()) {
            assertFalse(session.start().isBefore(time(8, 0)),
                    "A session must not start before free time");
            assertFalse(session.end().isAfter(time(10, 0)),
                    "A session must not end after free time");
            LocalTime previousEnd = previousEnds.get(session.date());
            if (previousEnd != null) {
                assertFalse(session.start().isBefore(previousEnd.plusMinutes(10)),
                        "Sessions on one day need a ten-minute break between them");
            }
            previousEnds.put(session.date(), session.end());
        }
    }

    @Test
    void schedulesWorkBeforeItsDeadline() {
        LocalDate deadline = TODAY.plusDays(2);
        FocusPlannerInput input = new FocusPlannerInput(
                List.of(),
                List.of(task("English", "Essay draft", FocusType.DEEP,
                        100, deadline, Priority.HIGH)));

        FocusPlan plan = planner.plan(input, preferences(8, 0, 22, 0), NOW);

        assertFalse(plan.sessions().isEmpty());
        for (FocusSession session : plan.sessions()) {
            assertFalse(session.date().isAfter(deadline),
                    "Every session must be on or before the deadline");
            assertEquals(Priority.HIGH, session.priority(),
                    "The task priority should reach the session");
            assertFalse(session.completed());
        }
        assertTrue(plan.warnings().isEmpty(),
                "A task that fits should not produce a warning");
    }

    @Test
    void reportsTasksWhoseDeadlineHasPassedInsteadOfSchedulingThemLate() {
        FocusPlannerInput input = new FocusPlannerInput(
                List.of(),
                List.of(task("History", "Read chapter 1", FocusType.QUICK,
                        25, TODAY.minusDays(1), Priority.MEDIUM)));

        FocusPlan plan = planner.plan(input, preferences(8, 0, 22, 0), NOW);

        assertTrue(plan.sessions().isEmpty());
        assertTrue(plan.warnings().stream()
                        .anyMatch(warning -> warning.contains("deadline has already passed")),
                "A missed deadline should be reported: " + plan.warnings());
    }

    @Test
    void spreadsSessionsAcrossSubjectsInsteadOfFillingOneSubjectFirst() {
        FocusPlannerInput input = new FocusPlannerInput(
                List.of(),
                List.of(
                        task("Alpha", "Read notes", FocusType.QUICK,
                                50, null, Priority.MEDIUM),
                        task("Beta", "Review lecture", FocusType.QUICK,
                                50, null, Priority.MEDIUM)));

        FocusPlan plan = planner.plan(input, preferences(8, 0, 12, 0), NOW);

        assertEquals(4, plan.sessions().size());
        assertEquals(List.of("Alpha", "Beta", "Alpha", "Beta"),
                plan.sessions().stream().map(FocusSession::subject).toList(),
                "Blocks should round-robin between subjects");
    }

    @Test
    void limitsEachSubjectToTwoSessionsPerDay() {
        FocusPlannerInput input = new FocusPlannerInput(
                List.of(),
                List.of(task("Math", "Practice", FocusType.QUICK,
                        200, null, Priority.MEDIUM)));

        FocusPlan plan = planner.plan(input, preferences(8, 0, 22, 0), NOW);

        assertEquals(8, plan.sessions().size(), "200 minutes should become eight blocks");
        plan.sessions().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        FocusSession::date,
                        java.util.stream.Collectors.counting()))
                .forEach((date, count) -> assertTrue(count <= 2,
                        "Only two Math sessions should be placed on " + date));
    }

    @Test
    void splitsLongTasksIntoEvenBlocks() {
        StudyTask deep = task("CS", "Build parser", FocusType.DEEP,
                120, TODAY.plusDays(3), Priority.HIGH);
        StudyTask quick = task("Math", "Flashcards", FocusType.QUICK,
                30, TODAY.plusDays(3), Priority.LOW);

        List<FocusPlanner.Chunk> deepChunks = FocusPlanner.split(deep);
        assertEquals(List.of(40, 40, 40),
                deepChunks.stream().map(FocusPlanner.Chunk::minutes).toList(),
                "A 120-minute deep task should split into three even 40-minute blocks");
        deepChunks.forEach(chunk -> assertSame(deep, chunk.task()));

        assertEquals(List.of(15, 15),
                FocusPlanner.split(quick).stream()
                        .map(FocusPlanner.Chunk::minutes).toList(),
                "A 30-minute quick task should split into two even 15-minute blocks");

        assertEquals(List.of(50), FocusPlanner.split(
                        task("Lab", "Experiment", FocusType.DEEP,
                                50, TODAY.plusDays(3), Priority.MEDIUM))
                .stream().map(FocusPlanner.Chunk::minutes).toList(),
                "A task that already fits should stay one block");
    }

    @Test
    void warnsWhenNoFreeSlotFits() {
        FocusPlannerInput input = new FocusPlannerInput(
                List.of(new ClassSlot("Lab", DayOfWeek.MONDAY,
                        time(8, 0), time(9, 0))),
                List.of(task("Lab", "Prepare report", FocusType.QUICK,
                        25, TODAY, Priority.MEDIUM)));

        FocusPlan plan = planner.plan(input, preferences(8, 0, 9, 0), NOW);

        assertTrue(plan.sessions().isEmpty());
        assertTrue(plan.warnings().stream()
                        .anyMatch(warning -> warning.contains("couldn't find a free slot")),
                "An impossible plan should explain why: " + plan.warnings());
    }

    private static FocusPreferences preferences(int fromHour, int fromMinute,
                                                int toHour, int toMinute) {
        return new FocusPreferences(time(fromHour, fromMinute), time(toHour, toMinute));
    }

    private static LocalTime time(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }

    private static StudyTask task(String subject, String title, FocusType type,
                                  int minutes, LocalDate deadline, Priority priority) {
        return new StudyTask(subject, title, type, minutes, deadline, priority);
    }
}
