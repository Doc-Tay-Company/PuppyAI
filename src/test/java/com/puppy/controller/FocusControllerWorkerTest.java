package com.puppy.controller;

import com.puppy.database.Database;
import com.puppy.database.PersistenceException;
import com.puppy.document.DocumentTextExtractor;
import com.puppy.enums.FocusType;
import com.puppy.model.FocusSession;
import com.puppy.model.FocusState;
import com.puppy.repository.FocusRepository;
import com.puppy.service.DefaultMascotStateService;
import com.puppy.service.FocusService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FocusControllerWorkerTest {
    @Test
    void workerCommitsBeforeSuccessAndTimerFailuresRemainRetryable() throws Exception {
        // The existing layout suite owns its own Platform.startup/exit lifecycle.
        // Isolate this probe so either test order remains safe.
        Path log = Path.of("target", "focus-worker-" + UUID.randomUUID() + ".log");
        String java = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", java).toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Focus worker probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
            Files.deleteIfExists(log);
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of("target", "focus-worker-db-" + UUID.randomUUID());
            Platform.startup(() -> { });
            try (Database database = Database.open(directory);
                 var worker = Executors.newSingleThreadExecutor()) {
                CountDownLatch saving = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                AtomicBoolean fail = new AtomicBoolean();
                FocusRepository repository = new FocusRepository(database) {
                    @Override
                    public void save(FocusState state) {
                        assertFalse(Platform.isFxApplicationThread());
                        saving.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        if (fail.get()) {
                            throw new PersistenceException("Injected Focus failure", null);
                        }
                        super.save(state);
                    }
                };
                FocusService service = new FocusService(documents -> {
                    throw new AssertionError("No extraction expected");
                }, new DocumentTextExtractor(), repository);
                FXMLLoader loader = new FXMLLoader(Probe.class.getResource("/fxml/focus.fxml"));
                FocusController controller = fx(() -> {
                    loader.load();
                    FocusController loaded = loader.getController();
                    loaded.attach(service, new DefaultMascotStateService(), worker,
                            () -> { }, () -> { }, () -> { });
                    return loaded;
                });
                try {
                    fx(() -> {
                        fillClass(loader, "Math");
                        invoke(controller, "onAddClass");
                        return null;
                    });
                    assertTrue(saving.await(5, TimeUnit.SECONDS));
                    fx(() -> {
                        assertEquals("Math", text(loader, "classSubject").getText());
                        assertEquals("Saving...", label(loader, "plannerStatus").getText());
                        assertTrue(((javafx.scene.control.TabPane) loader.getNamespace()
                                .get("plannerTabs")).isDisable());
                        return null;
                    });
                    release.countDown();
                    worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
                    fx(() -> {
                        assertTrue(text(loader, "classSubject").getText().isEmpty());
                        assertTrue(label(loader, "plannerStatus").getText().contains("Class added"));
                        return null;
                    });

                    fail.set(true);
                    fx(() -> {
                        fillClass(loader, "Physics");
                        invoke(controller, "onAddClass");
                        return null;
                    });
                    worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
                    fx(() -> {
                        assertEquals("Physics", text(loader, "classSubject").getText());
                        assertTrue(label(loader, "plannerStatus").getText().contains("Injected Focus failure"));
                        assertEquals(1, service.classes().size());
                        return null;
                    });

                    fail.set(false);
                    FocusSession session = service.addSession(FocusService.newManualSession(
                            "Math", "Review", FocusType.QUICK, LocalDate.now(), LocalTime.of(18, 0), 25));
                    fx(() -> {
                        controller.refresh();
                        var start = FocusController.class.getDeclaredMethod("onSessionStart", FocusSession.class);
                        start.setAccessible(true);
                        start.invoke(controller, session);
                        return null;
                    });
                    fail.set(true);
                    fx(() -> {
                        invoke(controller, "onFinishSession");
                        return null;
                    });
                    worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
                    fx(() -> {
                        assertEquals("Math", label(loader, "timerTitle").getText());
                        assertTrue(label(loader, "timerHint").getText().contains("retry"));
                        assertTrue(label(loader, "plannerStatus").getStyleClass().contains("error-copy"));
                        assertFalse(service.sessions().getFirst().completed());
                        return null;
                    });
                    fail.set(false);
                    fx(() -> {
                        invoke(controller, "onFinishSession");
                        return null;
                    });
                    worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
                    fx(() -> {
                        assertEquals("No session running", label(loader, "timerTitle").getText());
                        assertTrue(service.sessions().getFirst().completed());
                        return null;
                    });
                } finally {
                    release.countDown();
                    fx(() -> {
                        controller.close();
                        return null;
                    });
                }
            } finally {
                Platform.exit();
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }

        private static void fillClass(FXMLLoader loader, String subject) {
            text(loader, "classSubject").setText(subject);
            @SuppressWarnings("unchecked")
            ComboBox<DayOfWeek> day = (ComboBox<DayOfWeek>) loader.getNamespace().get("classDay");
            day.setValue(DayOfWeek.MONDAY);
            text(loader, "classStart").setText("09:00");
            text(loader, "classEnd").setText("10:00");
        }

        private static TextField text(FXMLLoader loader, String id) {
            return (TextField) loader.getNamespace().get(id);
        }

        private static Label label(FXMLLoader loader, String id) {
            return (Label) loader.getNamespace().get(id);
        }

        private static void invoke(FocusController controller, String name) throws Exception {
            var method = FocusController.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(controller);
        }

        private static <T> T fx(Callable<T> work) throws Exception {
            FutureTask<T> task = new FutureTask<>(work);
            Platform.runLater(task);
            return task.get(10, TimeUnit.SECONDS);
        }
    }
}
