package com.puppy.service;

import com.puppy.document.DocumentTextExtractor;
import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.DocumentAttachment;
import com.puppy.model.FocusExtraction;
import com.puppy.model.FocusPlan;
import com.puppy.model.FocusPlannerInput;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.StudyTask;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-16T07:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    @Test
    void mergesImportedClassesAndTasksWithoutDuplicates() throws Exception {
        AtomicReference<FocusExtraction> reply = new AtomicReference<>();
        FocusService service = service(documents -> reply.get());

        reply.set(extraction(
                List.of(new ClassSlot("Math", DayOfWeek.MONDAY,
                        time(9, 0), time(10, 0))),
                List.of(task("Math", "Read notes", FocusType.QUICK,
                        25, TODAY.plusDays(2)))));
        service.importDocuments(List.of(attachment("math.txt")), null);

        reply.set(extraction(
                List.of(new ClassSlot("Physics", DayOfWeek.TUESDAY,
                        time(13, 0), time(14, 30))),
                List.of(task("Physics", "Lab report", FocusType.DEEP,
                        100, TODAY.plusDays(3)))));
        service.importDocuments(List.of(attachment("physics.txt")), null);

        assertEquals(2, service.classes().size());
        assertEquals(2, service.tasks().size());
        assertEquals(1, service.currentPlan().sessions().stream()
                .filter(session -> session.subject().equals("Math")).count());

        // Importing the same facts a second time must not duplicate them.
        service.importDocuments(List.of(attachment("math.txt")), null);
        assertEquals(2, service.classes().size());
        assertEquals(2, service.tasks().size());
    }

    @Test
    void overrideForcesEveryImportedTaskToTheRequestedFocusType() throws Exception {
        FocusService service = service(documents -> extraction(
                List.of(),
                List.of(task("Math", "Read notes", FocusType.QUICK,
                        50, TODAY.plusDays(2)))));

        FocusPlan plan = service.importDocuments(
                List.of(attachment("tasks.txt")), FocusType.DEEP);

        assertTrue(service.tasks().stream()
                        .allMatch(task -> task.type() == FocusType.DEEP),
                "Every task should be forced to deep focus");
        assertFalse(plan.sessions().isEmpty());
        assertTrue(plan.sessions().stream()
                        .allMatch(session -> session.type() == FocusType.DEEP),
                "Planned sessions should follow the override");
    }

    @Test
    void addsAReviewTaskForATimetableWithoutTasks() throws Exception {
        FocusService service = service(documents -> extraction(
                List.of(new ClassSlot("Chemistry", DayOfWeek.WEDNESDAY,
                        time(8, 0), time(9, 30))),
                List.of()));

        service.importDocuments(List.of(attachment("timetable.txt")), null);

        assertEquals(1, service.tasks().size());
        StudyTask review = service.tasks().get(0);
        assertEquals("Chemistry", review.subject());
        assertEquals("Review class notes", review.title());
        assertEquals(FocusType.QUICK, review.type());
        assertEquals(TODAY.plusDays(6), review.deadline(),
                "Generated review work is due at the end of the default one-week horizon");

        assertEquals(2, service.currentPlan().sessions().size(),
                "A 50-minute quick review becomes two 25-minute sessions");
        assertTrue(service.currentPlan().sessions().stream()
                .allMatch(session -> session.subject().equals("Chemistry")));
    }

    @Test
    void updatesTasksWithTheSameNormalisedSubjectAndTitleInsteadOfDuplicating() throws Exception {
        AtomicReference<FocusExtraction> reply = new AtomicReference<>();
        FocusService service = service(documents -> reply.get());

        reply.set(extraction(List.of(), List.of(task("Toán cao cấp", "Ôn bài",
                FocusType.QUICK, 40, TODAY.plusDays(2)))));
        service.importDocuments(List.of(attachment("math.txt")), null);

        reply.set(extraction(List.of(), List.of(task("toan cao cap", "on bai",
                FocusType.DEEP, 90, TODAY.plusDays(3)))));
        service.importDocuments(List.of(attachment("math.txt")), null);

        assertEquals(1, service.tasks().size(), "The task should be updated, not duplicated");
        StudyTask updated = service.tasks().get(0);
        assertEquals(FocusType.DEEP, updated.type());
        assertEquals(90, updated.estimatedMinutes());
        assertEquals(TODAY.plusDays(3), updated.deadline());
    }

    @Test
    void addsAReviewTaskForANewTimetableSubjectWhenOtherTasksExist() throws Exception {
        AtomicReference<FocusExtraction> reply = new AtomicReference<>();
        FocusService service = service(documents -> reply.get());

        reply.set(extraction(List.of(), List.of(task("Math", "Read notes",
                FocusType.QUICK, 25, TODAY.plusDays(2)))));
        service.importDocuments(List.of(attachment("tasks.txt")), null);

        reply.set(extraction(List.of(new ClassSlot("Physics", DayOfWeek.TUESDAY,
                time(13, 0), time(14, 30))), List.of()));
        service.importDocuments(List.of(attachment("timetable.txt")), null);

        assertEquals(2, service.tasks().size(),
                "The existing Math task must survive and Physics needs a review task");
        assertTrue(service.tasks().stream().anyMatch(task ->
                task.subject().equals("Physics") && task.title().equals("Review class notes")));
    }

    @Test
    void lengthensAnEmphasisedSubjectWithoutDroppingTheOtherSubjects() throws Exception {
        FocusService service = service(documents -> extraction(
                List.of(
                        new ClassSlot("Physics", DayOfWeek.MONDAY, time(9, 0), time(10, 0)),
                        new ClassSlot("Chemistry", DayOfWeek.TUESDAY, time(9, 0), time(10, 0))),
                List.of(task("Math", "Read chapter 4", FocusType.QUICK,
                        40, TODAY.plusDays(2))),
                List.of("Math")));

        FocusPlan plan = service.importDocuments(
                List.of(attachment("timetable.txt")), null);

        StudyTask math = service.tasks().stream()
                .filter(task -> task.subject().equals("Math"))
                .findFirst().orElseThrow();
        assertEquals(70, math.estimatedMinutes(),
                "40 minutes gains 30 minutes because that is more than 50%");
        assertTrue(service.tasks().stream().anyMatch(task ->
                task.subject().equals("Physics") && task.title().equals("Review class notes")));
        assertTrue(service.tasks().stream().anyMatch(task ->
                task.subject().equals("Chemistry")
                        && task.title().equals("Review class notes")));
        assertTrue(plan.sessions().stream().map(FocusSession::subject).distinct().toList()
                        .containsAll(List.of("Math", "Physics", "Chemistry")),
                "Every subject should appear in the plan: "
                        + plan.sessions().stream().map(FocusSession::subject).toList());
    }

    @Test
    void doesNotLengthenAnEmphasisedReviewAgainWhenTheSameImportRepeats() throws Exception {
        FocusExtraction extraction = extraction(
                List.of(),
                List.of(task("Math", "Review class notes", FocusType.QUICK,
                        50, TODAY.plusDays(6))),
                List.of("Math"));
        FocusService service = service(documents -> extraction);

        service.importDocuments(List.of(attachment("request.txt")), null);
        service.importDocuments(List.of(attachment("request.txt")), null);

        assertEquals(1, service.tasks().size());
        assertEquals(80, service.tasks().get(0).estimatedMinutes(),
                "Repeated imports replace the task before applying the bonus");
    }

    @Test
    void manualSessionsMustFitFreeTimeClassesAndOtherSessions() throws Exception {
        FocusService service = service(documents -> {
            throw new AssertionError("No import should happen");
        });
        service.addClass(new ClassSlot("Math", DayOfWeek.MONDAY,
                time(9, 0), time(10, 0)));

        FocusPlanningException classOverlap = assertThrows(FocusPlanningException.class,
                () -> service.addSession(FocusService.newManualSession(
                        "Math", "Read notes", FocusType.QUICK, TODAY,
                        time(9, 30), 25)));
        assertTrue(classOverlap.getMessage().contains("overlaps your Math class"));

        FocusPlanningException outsideWindow = assertThrows(FocusPlanningException.class,
                () -> service.addSession(FocusService.newManualSession(
                        "Math", "Read notes", FocusType.QUICK, TODAY,
                        time(7, 30), 25)));
        assertTrue(outsideWindow.getMessage().contains("outside your free time"));

        FocusPlanningException past = assertThrows(FocusPlanningException.class,
                () -> service.addSession(FocusService.newManualSession(
                        "Math", "Read notes", FocusType.QUICK, TODAY.minusDays(1),
                        time(11, 0), 25)));
        assertTrue(past.getMessage().contains("past"));

        FocusSession created = service.addSession(FocusService.newManualSession(
                "Math", "Read notes", FocusType.QUICK, TODAY,
                time(11, 0), 25));
        assertEquals(1, service.sessions().size());
        assertEquals(time(11, 0), created.start());
        assertEquals(time(11, 25), created.end());

        FocusPlanningException sessionOverlap = assertThrows(FocusPlanningException.class,
                () -> service.addSession(FocusService.newManualSession(
                        "Math", "More notes", FocusType.QUICK, TODAY,
                        time(11, 10), 25)));
        assertTrue(sessionOverlap.getMessage().contains("overlaps another focus session"));
    }

    @Test
    void editsASessionAndPreservesCompletion() throws Exception {
        FocusService service = service(documents -> {
            throw new AssertionError("No import should happen");
        });
        FocusSession original = service.addSession(FocusService.newManualSession(
                "Math", "Read notes", FocusType.QUICK, TODAY, time(11, 0), 25));
        service.completeSession(original.id());

        FocusSession edited = service.updateSession(new FocusSession(
                original.id(), "Physics", "Lab report", FocusType.DEEP, TODAY,
                time(13, 0), 50, Priority.HIGH, false));

        assertEquals(original.id(), edited.id());
        assertEquals("Physics", edited.subject());
        assertEquals("Lab report", edited.title());
        assertEquals(FocusType.DEEP, edited.type());
        assertEquals(time(13, 0), edited.start());
        assertEquals(time(13, 50), edited.end());
        assertTrue(edited.completed(), "Editing must keep the completed flag");
        assertEquals(1, service.sessions().size());
        assertEquals(edited, service.sessions().get(0));
    }

    @Test
    void editsMayMoveInsideTheOriginalSessionTime() throws Exception {
        FocusService service = service(documents -> {
            throw new AssertionError("No import should happen");
        });
        FocusSession original = service.addSession(FocusService.newManualSession(
                "Math", "Read notes", FocusType.QUICK, TODAY, time(11, 0), 25));

        FocusSession moved = service.updateSession(new FocusSession(
                original.id(), "Math", "Read notes", FocusType.QUICK, TODAY,
                time(11, 10), 25, Priority.MEDIUM, false));

        assertEquals(time(11, 10), moved.start());
        assertEquals(time(11, 35), moved.end(),
                "The edited session should not collide with its own old time");
    }

    @Test
    void editsAreRejectedWhenTheyBreakPlacementRules() throws Exception {
        FocusService service = service(documents -> {
            throw new AssertionError("No import should happen");
        });
        service.addClass(new ClassSlot("Math", DayOfWeek.MONDAY,
                time(9, 0), time(10, 0)));
        FocusSession target = service.addSession(FocusService.newManualSession(
                "Math", "Read notes", FocusType.QUICK, TODAY, time(11, 0), 25));
        service.addSession(FocusService.newManualSession(
                "Physics", "Lab prep", FocusType.DEEP, TODAY, time(14, 0), 50));

        FocusPlanningException classOverlap = assertThrows(FocusPlanningException.class,
                () -> service.updateSession(new FocusSession(
                        target.id(), "Math", "Read notes", FocusType.QUICK, TODAY,
                        time(9, 30), 25, Priority.MEDIUM, false)));
        assertTrue(classOverlap.getMessage().contains("overlaps your Math class"));

        FocusPlanningException outsideWindow = assertThrows(FocusPlanningException.class,
                () -> service.updateSession(new FocusSession(
                        target.id(), "Math", "Read notes", FocusType.QUICK, TODAY,
                        time(7, 30), 25, Priority.MEDIUM, false)));
        assertTrue(outsideWindow.getMessage().contains("outside your free time"));

        FocusPlanningException sessionOverlap = assertThrows(FocusPlanningException.class,
                () -> service.updateSession(new FocusSession(
                        target.id(), "Math", "Read notes", FocusType.QUICK, TODAY,
                        time(14, 10), 25, Priority.MEDIUM, false)));
        assertTrue(sessionOverlap.getMessage().contains("overlaps another focus session"));

        FocusPlanningException past = assertThrows(FocusPlanningException.class,
                () -> service.updateSession(new FocusSession(
                        target.id(), "Math", "Read notes", FocusType.QUICK,
                        TODAY.minusDays(1), time(11, 0), 25, Priority.MEDIUM, false)));
        assertTrue(past.getMessage().contains("past"));

        FocusPlanningException missing = assertThrows(FocusPlanningException.class,
                () -> service.updateSession(new FocusSession(
                        "missing-session", "Math", "Read notes", FocusType.QUICK, TODAY,
                        time(16, 0), 25, Priority.MEDIUM, false)));
        assertTrue(missing.getMessage().contains("no longer in the plan"));

        FocusSession unchanged = service.sessions().stream()
                .filter(session -> session.id().equals(target.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(time(11, 0), unchanged.start(),
                "Rejected edits must leave the original session untouched");
    }

    @Test
    void completesAndRemovesASessionById() throws Exception {
        FocusService service = service(documents -> {
            throw new AssertionError("No import should happen");
        });
        FocusSession session = service.addSession(FocusService.newManualSession(
                "Physics", "Lab prep", FocusType.DEEP, TODAY,
                time(14, 0), 50));

        FocusSession completed = service.completeSession(session.id());

        assertTrue(completed.completed());
        assertTrue(service.sessions().get(0).completed());
        assertThrows(FocusPlanningException.class,
                () -> service.completeSession("missing-session"));

        service.removeSession(session.id());
        assertTrue(service.sessions().isEmpty());
    }

    @Test
    void generatePlanUsesTheConfiguredFreeTime() throws Exception {
        FocusService service = service(documents -> {
            throw new AssertionError("No import should happen");
        });
        service.setPreferences(new FocusPreferences(time(13, 0), time(18, 0)));
        service.addTask(task("Biology", "Review cells", FocusType.QUICK,
                25, TODAY.plusDays(1)));

        FocusPlan plan = service.generatePlan();

        assertFalse(plan.sessions().isEmpty());
        FocusSession session = plan.sessions().get(0);
        assertEquals(time(13, 0), session.start(),
                "The plan should start at the configured earliest time");
        assertFalse(session.end().isAfter(time(18, 0)));
    }

    @Test
    void usesAnExplicitPlanEndForTheNextPlanOnly() throws Exception {
        FocusService service = service(documents -> extraction(
                List.of(),
                List.of(task("Math", "Practice", FocusType.QUICK,
                        200, TODAY.plusDays(10)))));

        service.setPreferences(service.preferences().withPlanUntil(TODAY.plusDays(2)));
        FocusPlan plan = service.importDocuments(List.of(attachment("math.txt")), null);

        assertEquals(6, plan.sessions().size(),
                "Three days at two sessions per day cap the plan");
        assertTrue(plan.sessions().stream()
                        .allMatch(session -> !session.date().isAfter(TODAY.plusDays(2))),
                "The explicit plan end should bound the imported plan");
        assertNull(service.preferences().planUntil(),
                "The plan end belongs to one plan only");

        FocusPlan next = service.generatePlan();
        assertTrue(next.sessions().stream()
                        .anyMatch(session -> session.date().isAfter(TODAY.plusDays(2))),
                "A later plan should fall back to the default horizon");
    }

    @Test
    void prioritizingSubjectFitsIntoShortenedPlanWithoutWarnings() throws Exception {
        FocusService service = service(documents -> extraction(
                List.of(),
                List.of(task("Math", "Practice", FocusType.QUICK,
                        200, TODAY.plusDays(10)))));

        service.importDocuments(List.of(attachment("math.txt")), null);

        // When not prioritized and shortened to 2 days (TODAY + 1)
        service.setPreferences(service.preferences().withPlanUntil(TODAY.plusDays(1)));
        FocusPlan unprioritizedPlan = service.generatePlan();
        assertTrue(unprioritizedPlan.warnings().stream().anyMatch(w -> w.contains("could not be placed")),
                "Unprioritized tasks with short horizon should report unplaced minutes");

        // Now prioritize Math and set short horizon of 2 days
        service.setPreferences(service.preferences().withPlanUntil(TODAY.plusDays(1)));
        service.prioritizeSubject("Math");

        FocusPlan prioritizedPlan = service.currentPlan();
        assertTrue(prioritizedPlan.warnings().isEmpty(),
                "Prioritized Math should place all its sessions in the 2-day horizon without warnings: " + prioritizedPlan.warnings());
        assertTrue(prioritizedPlan.sessions().size() >= 8,
                "All chunks (including extra time bonus) should be scheduled");
        assertTrue(prioritizedPlan.sessions().stream()
                        .allMatch(session -> !session.date().isAfter(TODAY.plusDays(1))),
                "All sessions should be within the shortened horizon");
    }

    @Test
    void subjectsReturnsDistinctListOfTaskAndClassSubjects() throws Exception {
        FocusService service = service(documents -> extraction(
                List.of(new ClassSlot("Physics", DayOfWeek.MONDAY, time(9, 0), time(10, 30))),
                List.of(task("Math", "Practice", FocusType.QUICK, 60, null))));

        service.importDocuments(List.of(attachment("schedule.txt")), null);
        List<String> subjects = service.subjects();
        assertTrue(subjects.contains("Physics"));
        assertTrue(subjects.contains("Math"));
    }

    @Test
    void droppingSubjectRemovesAllTasksAndSessionsAndRedistributesTime() throws Exception {
        FocusService service = service(documents -> extraction(
                List.of(),
                List.of(
                        task("Math", "Calculus", FocusType.QUICK, 60, TODAY.plusDays(5)),
                        task("Literature", "Essay", FocusType.QUICK, 60, TODAY.plusDays(5)))));

        service.importDocuments(List.of(attachment("courses.txt")), null);
        assertEquals(2, service.subjects().size());
        assertTrue(service.sessions().stream().anyMatch(s -> s.subject().equals("Literature")));

        service.dropSubject("Literature");

        assertFalse(service.subjects().contains("Literature"), "Dropped subject must not appear in subjects");
        assertTrue(service.sessions().stream().noneMatch(s -> s.subject().equals("Literature")),
                "No session should remain for dropped subject");
        assertTrue(service.sessions().stream().anyMatch(s -> s.subject().equals("Math")),
                "Other subjects should remain and occupy the schedule");
    }

    @Test
    void deprioritizingSubjectResetsPriority() throws Exception {
        FocusService service = service(documents -> extraction(
                List.of(),
                List.of(task("Math", "Calculus", FocusType.QUICK, 60, TODAY.plusDays(5)))));

        service.importDocuments(List.of(attachment("courses.txt")), null);
        service.prioritizeSubject("Math");
        assertTrue(service.prioritizedSubjects().contains("Math"));

        service.deprioritizeSubject("Math");
        assertFalse(service.prioritizedSubjects().contains("Math"));
    }

    private static FocusService service(FocusPlanExtractor extractor) {
        return new FocusService(extractor, new DocumentTextExtractor(), CLOCK);
    }

    private static FocusExtraction extraction(List<ClassSlot> classes, List<StudyTask> tasks) {
        return new FocusExtraction(new FocusPlannerInput(classes, tasks), List.of());
    }

    private static FocusExtraction extraction(List<ClassSlot> classes, List<StudyTask> tasks,
                                              List<String> moreTimeSubjects) {
        return new FocusExtraction(
                new FocusPlannerInput(classes, tasks, moreTimeSubjects), List.of());
    }

    private static DocumentAttachment attachment(String name) {
        return new DocumentAttachment(Path.of(name), "timetable text", 1, false);
    }

    private static StudyTask task(String subject, String title, FocusType type,
                                  int minutes, LocalDate deadline) {
        return new StudyTask(subject, title, type, minutes, deadline, Priority.MEDIUM);
    }

    private static LocalTime time(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }
}
