package com.puppy.service;

import com.puppy.database.Database;
import com.puppy.document.DocumentTextExtractor;
import com.puppy.enums.FocusSessionState;
import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.DocumentAttachment;
import com.puppy.model.FocusExtraction;
import com.puppy.model.FocusPlanDraft;
import com.puppy.model.FocusPlannerInput;
import com.puppy.model.FocusPreferences;
import com.puppy.model.StudyTask;
import com.puppy.repository.FocusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusDraftWorkflowTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void previewDoesNotMutateAndConfirmationPersistsAtomically() throws Exception {
        try (Database database = Database.open(directory)) {
            FocusRepository repository = new FocusRepository(database);
            StudyTask task = new StudyTask("Java", "Practice OOP", FocusType.DEEP,
                    100, LocalDate.of(2026, 11, 22), Priority.HIGH);
            FocusService service = service(repository, task);
            FocusPreferences requested = new FocusPreferences(
                    LocalTime.of(18, 0), LocalTime.of(22, 0), null,
                    LocalDate.of(2026, 11, 22), 15, 60);

            FocusPlanDraft draft = service.previewDocuments(
                    List.of(new DocumentAttachment(Path.of("request.txt"), "Java", 0, false)),
                    requested);

            assertTrue(service.tasks().isEmpty(), "Preview must not alter committed tasks");
            assertTrue(service.sessions().isEmpty(), "Preview must not alter committed sessions");
            assertFalse(draft.plan().sessions().isEmpty());
            assertEquals(15, draft.proposed().preferences().breakMinutes());

            service.confirmDraft(draft);

            assertEquals(1, service.tasks().size());
            assertEquals(draft.plan().sessions(), service.sessions());
            assertEquals(service.currentPlan(), repository.load().orElseThrow().plan());
        }
    }

    @Test
    void stalePreviewCannotOverwriteANewerPlan() throws Exception {
        try (Database database = Database.open(directory)) {
            FocusService service = service(new FocusRepository(database), new StudyTask(
                    "Math", "Exercises", FocusType.QUICK, 25,
                    LocalDate.of(2026, 11, 20), Priority.MEDIUM));
            FocusPlanDraft draft = service.previewDocuments(
                    List.of(new DocumentAttachment(Path.of("request.txt"), "Math", 0, false)),
                    new FocusPreferences(LocalTime.of(18, 0), LocalTime.of(22, 0)));
            service.addTask(new StudyTask("Physics", "Read notes", FocusType.QUICK,
                    25, null, Priority.LOW));

            assertThrows(FocusPlanningException.class, () -> service.confirmDraft(draft));
        }
    }

    @Test
    void lifecycleAndCompletedHistorySurviveRegeneration() throws Exception {
        try (Database database = Database.open(directory)) {
            FocusRepository repository = new FocusRepository(database);
            FocusService service = service(repository, null);
            service.setPreferences(new FocusPreferences(LocalTime.of(8, 0), LocalTime.of(12, 0)));
            service.addTask(new StudyTask("Math", "Practice", FocusType.QUICK, 25,
                    LocalDate.of(2026, 11, 20), Priority.MEDIUM));
            var session = service.generatePlan().sessions().getFirst();

            assertEquals(FocusSessionState.ACTIVE,
                    service.setSessionState(session.id(), FocusSessionState.ACTIVE).state());
            assertEquals(FocusSessionState.PAUSED,
                    service.setSessionState(session.id(), FocusSessionState.PAUSED).state());
            service.setSessionState(session.id(), FocusSessionState.ACTIVE);
            service.completeSession(session.id());
            service.generatePlan();

            assertEquals(1, service.sessions().size());
            assertEquals(session.id(), service.sessions().getFirst().id());
            assertEquals(FocusSessionState.COMPLETED, service.sessions().getFirst().state());

            FocusService restored = service(repository, null);
            assertEquals(FocusSessionState.COMPLETED, restored.sessions().getFirst().state());
        }
    }

    @Test
    void examModeHonorsCustomBreaksAndSessionLimit() {
        StudyTask exam = new StudyTask("Biology", "Mock exam", FocusType.EXAM, 80,
                LocalDate.of(2026, 11, 19), Priority.HIGH);
        FocusPreferences preferences = new FocusPreferences(
                LocalTime.of(8, 0), LocalTime.of(12, 0), null,
                LocalDate.of(2026, 11, 19), 20, 40);

        var plan = new FocusPlanner().plan(
                new FocusPlannerInput(List.of(), List.of(exam)), preferences,
                LocalDateTime.of(2026, 11, 19, 7, 0));

        assertEquals(2, plan.sessions().size());
        assertTrue(plan.sessions().stream().allMatch(session ->
                session.type() == FocusType.EXAM && session.durationMinutes() <= 40));
        assertEquals(60, java.time.Duration.between(
                plan.sessions().get(0).start(), plan.sessions().get(1).start()).toMinutes());
    }

    private FocusService service(FocusRepository repository, StudyTask extracted) {
        return new FocusService(documents -> new FocusExtraction(
                new FocusPlannerInput(List.of(),
                        extracted == null ? List.of() : List.of(extracted)), List.of()),
                new DocumentTextExtractor(), clock, repository);
    }
}
