package com.puppy.service;

import com.puppy.document.DocumentTextExtractor;
import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.FocusEditAction;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.StudyTask;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusServiceEditActionTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-16T07:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    @Test
    void appliesUpdateActionToExistingSession() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);

        // Add an initial session
        FocusSession session1 = FocusService.newManualSession(
                "Math", "Calculus review", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(9, 0), 50);
        service.addSession(session1);

        assertEquals(1, service.sessions().size());

        // Apply update action
        FocusEditAction update = new FocusEditAction(
                FocusEditAction.Type.UPDATE, 1, "Math", "Calculus review",
                FocusType.DEEP, TODAY.plusDays(1), LocalTime.of(19, 0), 60, Priority.HIGH);

        List<FocusSession> updated = service.applyEditActions(List.of(update));

        assertEquals(1, updated.size());
        FocusSession result = updated.get(0);
        assertEquals(LocalTime.of(19, 0), result.start());
        assertEquals(60, result.durationMinutes());
        assertEquals(FocusType.DEEP, result.type());
    }

    @Test
    void appliesRemoveAction() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);

        service.addSession(FocusService.newManualSession(
                "Math", "Review", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(9, 0), 30));
        service.addSession(FocusService.newManualSession(
                "Physics", "Lab", FocusType.DEEP,
                TODAY.plusDays(1), LocalTime.of(10, 0), 50));

        assertEquals(2, service.sessions().size());

        FocusEditAction remove = FocusEditAction.remove(1);
        List<FocusSession> remaining = service.applyEditActions(List.of(remove));

        assertEquals(1, remaining.size());
        assertEquals("Physics", remaining.get(0).subject());
    }

    @Test
    void removesMultipleSessionsWithoutIndexShift() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);

        service.addSession(FocusService.newManualSession(
                "Math", "Review", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(9, 0), 30));
        service.addSession(FocusService.newManualSession(
                "Physics", "Lab", FocusType.DEEP,
                TODAY.plusDays(1), LocalTime.of(10, 0), 50));
        service.addSession(FocusService.newManualSession(
                "Chemistry", "Quiz", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(11, 0), 40));

        assertEquals(3, service.sessions().size());

        // Remove session 1 and session 2 based on original plan index
        List<FocusEditAction> removes = List.of(
                FocusEditAction.remove(1),
                FocusEditAction.remove(2));

        List<FocusSession> remaining = service.applyEditActions(removes);

        assertEquals(1, remaining.size());
        assertEquals("Chemistry", remaining.get(0).subject());
    }

    @Test
    void appliesAddAction() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);

        FocusEditAction add = FocusEditAction.add(
                "Literature", "Essay drafting", FocusType.DEEP,
                TODAY.plusDays(2), LocalTime.of(15, 0), 50, Priority.MEDIUM);

        List<FocusSession> sessions = service.applyEditActions(List.of(add));

        assertEquals(1, sessions.size());
        FocusSession added = sessions.get(0);
        assertEquals("Literature", added.subject());
        assertEquals("Essay drafting", added.title());
        assertEquals(LocalTime.of(15, 0), added.start());
    }

    @Test
    void rejectsInvalidIndexOrOverlap() {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);

        // No sessions exist
        FocusEditAction invalidUpdate = new FocusEditAction(
                FocusEditAction.Type.UPDATE, 5, "Math", null, null, null, null, null, null);

        assertThrows(FocusPlanningException.class, () ->
                service.applyEditActions(List.of(invalidUpdate)));
    }

    @Test
    void appliesMoveResizeAndSubjectChangeInOneBatch() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);
        service.addSession(FocusService.newManualSession(
                "Math", "Review", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(9, 0), 50));

        List<FocusSession> saved = service.applyEdits(List.of(
                FocusEditAction.move(1, TODAY.plusDays(2), LocalTime.of(19, 0)),
                FocusEditAction.resize(1, 75),
                FocusEditAction.changeSubject(1, "Physics")));

        assertEquals(1, saved.size());
        FocusSession result = saved.getFirst();
        assertEquals("Physics", result.subject());
        assertEquals(LocalDate.of(2026, 11, 18), result.date());
        assertEquals(LocalTime.of(19, 0), result.start());
        assertEquals(75, result.durationMinutes());
        assertTrue(service.hasPlanEdits());
        assertTrue(service.canUndo());
    }

    @Test
    void setWindowUpdatesPreferencesAndRejectsSessionsOutsideIt() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);
        service.addSession(FocusService.newManualSession(
                "Math", "Review", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(9, 0), 50));

        assertThrows(FocusPlanningException.class, () -> service.applyEdits(List.of(
                FocusEditAction.setWindow(LocalTime.of(10, 0), LocalTime.of(12, 0)))));
        assertEquals(FocusPreferences.defaults(), service.preferences(),
                "A rejected window must not be saved");
        assertFalse(service.preferencesConfigured());

        service.applyEdits(List.of(
                FocusEditAction.setWindow(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                FocusEditAction.add("Chemistry", "Lab", FocusType.DEEP,
                        TODAY.plusDays(2), LocalTime.of(19, 0), 45, Priority.HIGH)));

        assertEquals(LocalTime.of(8, 0), service.preferences().earliest());
        assertEquals(LocalTime.of(20, 0), service.preferences().latest());
        assertTrue(service.preferencesConfigured());
        assertEquals(2, service.sessions().size());
    }

    @Test
    void editsBreakLimitModeAndDurableTasksAtomically() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);
        service.addTask(new StudyTask("Math", "Review", FocusType.QUICK, 25,
                TODAY.plusDays(2), Priority.MEDIUM));
        service.addSession(FocusService.newManualSession(
                "Math", "Review", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(9, 0), 25));

        service.applyEdits(List.of(
                new FocusEditAction(FocusEditAction.Type.CHANGE_TYPE, 1,
                        null, null, FocusType.EXAM, null, null, null, null),
                FocusEditAction.setBreak(15),
                FocusEditAction.setSessionLimit(60),
                new FocusEditAction(FocusEditAction.Type.PRIORITIZE_SUBJECT, 0,
                        "Math", null, null, null, null, null, Priority.HIGH),
                new FocusEditAction(FocusEditAction.Type.ADD_TASK, 0,
                        "Chemistry", "Review acids", FocusType.QUICK,
                        TODAY.plusDays(2), LocalTime.of(11, 0), 25, Priority.MEDIUM)));

        assertEquals(15, service.preferences().breakMinutes());
        assertEquals(60, service.preferences().maxSessionMinutes());
        assertEquals(FocusType.EXAM, service.sessions().stream()
                .filter(session -> session.subject().equals("Math"))
                .findFirst().orElseThrow().type());
        assertTrue(service.tasks().stream().anyMatch(task ->
                task.subject().equals("Chemistry") && task.title().equals("Review acids")));
        assertTrue(service.tasks().stream().filter(task -> task.subject().equals("Math"))
                .allMatch(task -> task.priority() == Priority.HIGH));

        assertTrue(service.undoLastEdit());
        assertEquals(1, service.tasks().size(), "Undo must restore task metadata too");
        assertEquals(10, service.preferences().breakMinutes());
    }

    @Test
    void invalidActionLeavesTheWholeBatchUntouched() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);
        service.addSession(FocusService.newManualSession(
                "Math", "Review", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(9, 0), 50));
        service.addSession(FocusService.newManualSession(
                "Physics", "Lab", FocusType.DEEP,
                TODAY.plusDays(1), LocalTime.of(10, 0), 50));
        service.addSession(FocusService.newManualSession(
                "Chemistry", "Quiz", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(12, 0), 40));
        List<FocusSession> before = service.sessions();

        assertThrows(FocusPlanningException.class, () -> service.applyEdits(List.of(
                FocusEditAction.move(1, TODAY.plusDays(1), LocalTime.of(10, 0)),
                FocusEditAction.resize(3, 60))));

        assertEquals(before, service.sessions(),
                "One overlap must roll back every action in the batch");
        assertEquals(FocusPreferences.defaults(), service.preferences());
    }

    @Test
    void undoRestoresSessionsAndFreeTimeWindow() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);
        service.addSession(FocusService.newManualSession(
                "Math", "Review", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(9, 0), 50));

        service.applyEdits(List.of(FocusEditAction.resize(1, 90)));
        assertEquals(90, service.sessions().getFirst().durationMinutes());
        assertTrue(service.undoLastEdit());
        assertEquals(50, service.sessions().getFirst().durationMinutes());
        assertFalse(service.canUndo(), "Undo is consumed after one use");
        assertFalse(service.undoLastEdit());

        service.applyEdits(List.of(
                FocusEditAction.setWindow(LocalTime.of(9, 0), LocalTime.of(21, 0))));
        assertEquals(LocalTime.of(21, 0), service.preferences().latest());
        assertTrue(service.undoLastEdit());
        assertEquals(FocusPreferences.defaults(), service.preferences());
        assertFalse(service.preferencesConfigured());
    }

    @Test
    void applyToAllSubjectsKeepsDatesTitlesAndOtherSubjects() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);
        FocusSession first = service.addSession(FocusService.newManualSession(
                "Math", "Review", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(9, 0), 50));
        service.addSession(FocusService.newManualSession(
                "Math", "Practice", FocusType.QUICK,
                TODAY.plusDays(2), LocalTime.of(9, 0), 50));
        service.addSession(FocusService.newManualSession(
                "Physics", "Lab", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(11, 0), 50));

        FocusSession draft = new FocusSession(first.id(), "Math", "Edited",
                FocusType.DEEP, first.date(), LocalTime.of(14, 0), 60,
                Priority.HIGH, false);
        List<FocusSession> saved = service.updateSessions(draft, true);

        assertEquals(2, saved.size(), "Only Math sessions should be updated");
        List<FocusSession> math = service.sessions().stream()
                .filter(session -> session.subject().equals("Math"))
                .toList();
        assertEquals(2, math.size());
        assertTrue(math.stream().allMatch(session ->
                session.start().equals(LocalTime.of(14, 0))
                        && session.durationMinutes() == 60
                        && session.type() == FocusType.DEEP));
        assertEquals(List.of(TODAY.plusDays(1), TODAY.plusDays(2)),
                math.stream().map(FocusSession::date).sorted().toList());
        assertEquals(List.of("Edited", "Practice"),
                math.stream().map(FocusSession::title).sorted().toList());
        FocusSession physics = service.sessions().stream()
                .filter(session -> session.subject().equals("Physics"))
                .findFirst().orElseThrow();
        assertEquals(LocalTime.of(11, 0), physics.start());
        assertEquals(50, physics.durationMinutes());
        assertTrue(service.undoLastEdit());
        assertEquals(LocalTime.of(9, 0), service.sessions().stream()
                .filter(session -> session.subject().equals("Math"))
                .findFirst().orElseThrow().start());
    }

    @Test
    void applyToAllSubjectsIsAtomicWhenOneSessionWouldOverlap() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);
        FocusSession first = service.addSession(FocusService.newManualSession(
                "Math", "Review", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(9, 0), 50));
        service.addSession(FocusService.newManualSession(
                "Math", "Practice", FocusType.QUICK,
                TODAY.plusDays(2), LocalTime.of(9, 0), 50));
        service.addSession(FocusService.newManualSession(
                "Physics", "Lab", FocusType.QUICK,
                TODAY.plusDays(2), LocalTime.of(8, 0), 60));
        List<FocusSession> before = service.sessions();

        FocusSession draft = new FocusSession(first.id(), "Math", "Review",
                FocusType.QUICK, first.date(), LocalTime.of(8, 0), 50,
                Priority.MEDIUM, false);

        assertThrows(FocusPlanningException.class,
                () -> service.updateSessions(draft, true));
        assertEquals(before, service.sessions(),
                "A single conflicting session must roll back the whole subject batch");
    }

    @Test
    void rejectsClassOverlapAndPastDatesWithoutChangingThePlan() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);
        service.addClass(new ClassSlot("Math", DayOfWeek.MONDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0)));
        service.addSession(FocusService.newManualSession(
                "Physics", "Lab", FocusType.DEEP,
                TODAY.plusDays(7), LocalTime.of(14, 0), 50));
        List<FocusSession> before = service.sessions();

        assertThrows(FocusPlanningException.class, () -> service.applyEdits(List.of(
                FocusEditAction.move(1, TODAY.plusDays(7), LocalTime.of(9, 30)))),
                "A session must not overlap a class");
        assertEquals(before, service.sessions());

        assertThrows(FocusPlanningException.class, () -> service.applyEdits(List.of(
                FocusEditAction.move(1, TODAY.minusDays(1), LocalTime.of(14, 0)))),
                "An edit must not schedule into the past");
        assertEquals(before, service.sessions());
    }

    @Test
    void notifiesPlanChangeListenerOnEdit() throws Exception {
        FocusService service = new FocusService(
                docs -> null, new DocumentTextExtractor(), CLOCK);

        service.addSession(FocusService.newManualSession(
                "Math", "Review", FocusType.QUICK,
                TODAY.plusDays(1), LocalTime.of(9, 0), 30));

        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        service.addPlanChangeListener(plan -> callCount.incrementAndGet());

        FocusEditAction update = new FocusEditAction(
                FocusEditAction.Type.UPDATE, 1, "Math", "New title",
                FocusType.DEEP, TODAY.plusDays(1), LocalTime.of(19, 0), 50, Priority.HIGH);

        service.applyEditActions(List.of(update));

        assertEquals(1, callCount.get());
    }
}
