package com.puppy.service;

import com.puppy.database.Database;
import com.puppy.database.PersistenceException;
import com.puppy.document.DocumentTextExtractor;
import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.DocumentAttachment;
import com.puppy.model.FocusExtraction;
import com.puppy.model.FocusPlannerInput;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.FocusState;
import com.puppy.model.StudyTask;
import com.puppy.repository.FocusRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FocusPersistenceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-16T07:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private static final ClassSlot CLASS = new ClassSlot("Math", DayOfWeek.MONDAY,
            LocalTime.of(9, 0), LocalTime.of(10, 0));
    private static final StudyTask TASK = new StudyTask("Math", "Review", FocusType.QUICK,
            50, TODAY.plusDays(3), Priority.HIGH);
    private Path directory;

    @BeforeEach
    void createStorage() throws Exception {
        directory = Path.of("target", "focus-persistence-" + UUID.randomUUID());
        Files.createDirectories(directory);
    }

    @AfterEach
    void deleteStorage() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void restartRestoresInputsStableIdsWarningsAndHistoricalCompletedSessions() throws Exception {
        FocusState expected;
        try (Database database = Database.open(directory)) {
            FocusRepository repository = new FocusRepository(database);
            FocusService service = service(repository, CLOCK);
            assertTrue(repository.load().isEmpty());
            service.setPreferences(new FocusPreferences(LocalTime.of(11, 0),
                    LocalTime.of(20, 0), TODAY.plusDays(1)));
            assertEquals(service.preferences(), service(repository, CLOCK).preferences(),
                    "An unconsumed explicit plan end must survive restoration");
            service.importDocuments(documents());
            assertNull(service.preferences().planUntil());
            FocusState imported = repository.load().orElseThrow();
            service.importDocuments(documents());
            assertEquals(imported.classes(), repository.load().orElseThrow().classes());
            assertEquals(imported.tasks(), repository.load().orElseThrow().tasks());
            service.importDocuments(documents(), FocusType.DEEP);
            assertEquals(imported.tasks().getFirst().id(),
                    repository.load().orElseThrow().tasks().getFirst().id(),
                    "Changing a task's focus type must keep its durable identity");
            FocusSession manual = service.addSession(manual());
            service.completeSession(manual.id());
            service.updateSession(new FocusSession(manual.id(), "Physics", "Edited",
                    FocusType.DEEP, TODAY, LocalTime.of(18, 0), 50, Priority.HIGH, false));
            expected = repository.load().orElseThrow();
            assertEquals(List.of("Imported note"), expected.plan().warnings());
        }
        try (Database database = Database.open(directory)) {
            FocusRepository repository = new FocusRepository(database);
            FocusService restored = service(repository, Clock.offset(CLOCK, java.time.Duration.ofDays(30)));
            assertState(expected, restored);
            assertEquals(expected, repository.load().orElseThrow());
            assertEquals(TODAY, restored.currentPlan().generatedOn());
            assertTrue(restored.sessions().stream().anyMatch(session ->
                    session.id().equals("manual-id") && session.completed()
                            && session.subject().equals("Physics")));
        }
    }

    @Test
    void removalsClearingAndRegenerationRemainDurable() throws Exception {
        try (Database database = Database.open(directory)) {
            FocusRepository repository = new FocusRepository(database);
            FocusService service = service(repository, CLOCK);
            service.addClass(CLASS);
            service.addTask(TASK);
            service.addSession(manual());
            service.generatePlan();
            assertFalse(service.sessions().stream().anyMatch(s -> s.id().equals("manual-id")));
            String removedId = service.sessions().getFirst().id();
            service.removeSession(removedId);
            assertState(repository.load().orElseThrow(), service(repository, CLOCK));
            assertFalse(service(repository, CLOCK).sessions().stream().anyMatch(s -> s.id().equals(removedId)));
            service.removeClass(CLASS);
            service.removeTask(TASK);
            service.clearSessions();
        }
        try (Database database = Database.open(directory)) {
            FocusService restored = service(new FocusRepository(database), CLOCK);
            assertTrue(restored.classes().isEmpty());
            assertTrue(restored.tasks().isEmpty());
            assertTrue(restored.sessions().isEmpty());
            assertTrue(restored.currentPlan().warnings().getFirst().contains("session list is empty"));
        }
    }

    @Test
    void configuredWindowAndPlanEditsSurviveRestart() throws Exception {
        try (Database database = Database.open(directory)) {
            FocusRepository repository = new FocusRepository(database);
            FocusService service = service(repository, CLOCK);
            service.setPreferences(new FocusPreferences(
                    LocalTime.of(9, 0), LocalTime.of(22, 0)));
            service.addSession(manual());
            assertTrue(service.preferencesConfigured());
            assertTrue(service.hasPlanEdits());
            assertTrue(service.canUndo());

            FocusService restored = service(repository, CLOCK);
            assertTrue(restored.preferencesConfigured(),
                    "Chat must reuse a window the user already saved");
            assertTrue(restored.hasPlanEdits(),
                    "Generate and Import should warn before replacing edits");
            assertFalse(restored.canUndo(), "Undo is deliberately transient");
        }
    }

    @Test
    void olderStatesWithoutFlagsInferAConfiguredWindow() throws Exception {
        try (Database database = Database.open(directory)) {
            FocusRepository repository = new FocusRepository(database);
            FocusPreferences custom = new FocusPreferences(
                    LocalTime.of(17, 0), LocalTime.of(21, 0));
            repository.save(new FocusState(List.of(), List.of(), custom,
                    com.puppy.model.FocusPlan.empty(TODAY, "Legacy state")));

            FocusService restored = service(repository, CLOCK);
            assertEquals(custom, restored.preferences());
            assertTrue(restored.preferencesConfigured(),
                    "A non-default saved window means the user chose it");
            assertFalse(restored.hasPlanEdits());
            assertFalse(restored.canUndo());
        }
    }

    @Test
    void payloadsWrittenBeforeTheFlagsExistedStillLoad() throws Exception {
        try (Database database = Database.open(directory)) {
            FocusRepository repository = new FocusRepository(database);
            FocusPreferences custom = new FocusPreferences(
                    LocalTime.of(10, 0), LocalTime.of(20, 0));
            repository.save(new FocusState(List.of(), List.of(), custom,
                    com.puppy.model.FocusPlan.empty(TODAY, "Legacy state"), true, true));
            removeFlagFields(database);

            FocusService restored = service(repository, CLOCK);
            assertEquals(custom, restored.preferences());
            assertTrue(restored.preferencesConfigured(),
                    "An older payload has no flag, so the window itself is the evidence");
            assertFalse(restored.hasPlanEdits(),
                    "Missing edit flags must default to false");
        }
    }

    private static void removeFlagFields(Database database) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        database.transaction(connection -> {
            String payload;
            try (var select = connection.prepareStatement(
                    "SELECT payload FROM focus_state WHERE singleton = 1");
                 var rows = select.executeQuery()) {
                rows.next();
                payload = rows.getString(1);
            }
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(payload);
            node.remove("preferencesConfigured");
            node.remove("planEdited");
            try (var update = connection.prepareStatement(
                    "UPDATE focus_state SET payload = ? WHERE singleton = 1")) {
                update.setString(1, mapper.writeValueAsString(node));
                update.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void failedSavesRollbackEveryMutationIncludingImportsAndConsumedPlanEnd() throws Exception {
        try (Database database = Database.open(directory)) {
            FocusRepository repository = new FocusRepository(database);
            FocusService service = service(repository, CLOCK);
            service.addClass(CLASS);
            service.addTask(TASK);
            service.addSession(manual());
            service.setPreferences(service.preferences().withPlanUntil(TODAY.plusDays(2)));
            FocusState before = repository.load().orElseThrow();
            database.transaction(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TRIGGER reject_focus_save BEFORE UPDATE ON focus_state "
                            + "BEGIN SELECT RAISE(ABORT, 'Injected Focus save failure'); END");
                }
                return null;
            });
            List<Executable> mutations = List.of(
                    () -> service.addClass(new ClassSlot("Physics", DayOfWeek.TUESDAY,
                            LocalTime.of(9, 0), LocalTime.of(10, 0))),
                    () -> service.removeClass(CLASS),
                    () -> service.addTask(TASK.withType(FocusType.DEEP)),
                    () -> service.removeTask(TASK),
                    () -> service.setPreferences(new FocusPreferences(LocalTime.NOON, LocalTime.of(21, 0))),
                    service::generatePlan,
                    () -> service.importDocuments(documents(), FocusType.DEEP),
                    () -> service.addSession(new FocusSession("other-id", "Physics", "Review", FocusType.QUICK,
                            TODAY, LocalTime.of(15, 0), 25, Priority.LOW, false)),
                    () -> service.updateSession(new FocusSession("manual-id", "Physics", "Edit", FocusType.QUICK,
                            TODAY, LocalTime.of(18, 0), 25, Priority.LOW, false)),
                    () -> service.completeSession("manual-id"),
                    () -> service.removeSession("manual-id"),
                    service::clearSessions);
            for (Executable mutation : mutations) {
                assertThrows(PersistenceException.class, mutation);
                assertState(before, service);
                assertEquals(before, repository.load().orElseThrow());
            }
            database.transaction(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("DROP TRIGGER reject_focus_save");
                }
                return null;
            });
            service.generatePlan();
            assertNull(service.preferences().planUntil(), "A retry must still consume the original plan end");
        }
    }

    @Test
    void gettersCannotObserveAnUncommittedMutation() throws Exception {
        try (Database database = Database.open(directory);
             var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch saving = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean fail = new AtomicBoolean();
            FocusRepository repository = new FocusRepository(database) {
                @Override
                public void save(FocusState state) {
                    if (fail.get()) {
                        saving.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("Save was not released");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        throw new PersistenceException("Injected failure", null);
                    }
                    super.save(state);
                }
            };
            FocusService service = service(repository, CLOCK);
            fail.set(true);
            var mutation = workers.submit(() -> assertThrows(PersistenceException.class,
                    () -> service.addTask(TASK)));
            try {
                assertTrue(saving.await(5, TimeUnit.SECONDS));
                CountDownLatch reading = new CountDownLatch(1);
                var reader = workers.submit(() -> {
                    reading.countDown();
                    return service.tasks();
                });
                assertTrue(reading.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> reader.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                mutation.get(5, TimeUnit.SECONDS);
                assertTrue(reader.get(5, TimeUnit.SECONDS).isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    private static FocusService service(FocusRepository repository, Clock clock) {
        return new FocusService(documents -> new FocusExtraction(
                new FocusPlannerInput(List.of(CLASS), List.of(TASK)), List.of("Imported note")),
                new DocumentTextExtractor(), clock, repository);
    }

    private static List<DocumentAttachment> documents() {
        return List.of(new DocumentAttachment(Path.of("timetable.txt"), "Timetable", 1, false));
    }

    private static FocusSession manual() {
        return new FocusSession("manual-id", "Math", "Manual", FocusType.QUICK,
                TODAY, LocalTime.of(17, 0), 25, Priority.MEDIUM, false);
    }

    private static void assertState(FocusState expected, FocusService actual) {
        assertEquals(expected.classes().stream().map(FocusState.ClassEntry::value).toList(), actual.classes());
        assertEquals(expected.tasks().stream().map(FocusState.TaskEntry::value).toList(), actual.tasks());
        assertEquals(expected.preferences(), actual.preferences());
        assertEquals(expected.plan(), actual.currentPlan());
    }
}
