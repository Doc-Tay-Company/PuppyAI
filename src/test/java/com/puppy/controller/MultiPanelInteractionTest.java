package com.puppy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppy.config.AppConfig;
import com.puppy.config.ServiceRegistry;
import com.puppy.enums.FocusType;
import com.puppy.enums.MascotState;
import com.puppy.service.FocusService;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class MultiPanelInteractionTest {
    @TempDir Path directory;

    @Test
    void independentWindowsKeepStateAndBackgroundWork() throws Exception {
        Path log = directory.resolve("multi-panel.log");
        String java = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", java).toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(), directory.resolve("data").toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Multi-panel probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) { process.destroyForcibly().waitFor(); }
        }
    }

    public static final class Probe {
        private static final AtomicReference<CountDownLatch> entered = new AtomicReference<>(new CountDownLatch(1));
        private static final AtomicReference<CountDownLatch> release = new AtomicReference<>(new CountDownLatch(1));

        public static void main(String[] args) throws Exception {
            Platform.startup(() -> { });
            Platform.setImplicitExit(false);
            var json = new ObjectMapper();
            var workers = Executors.newCachedThreadPool();
            HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/api/chat", exchange -> {
                var request = json.readTree(exchange.getRequestBody());
                boolean stream = request.path("stream").asBoolean();
                if (stream) {
                    CountDownLatch gate = release.get();
                    entered.get().countDown();
                    try { gate.await(20, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                String content = stream ? "Background reply completed." : "{\"title\":\"Background practice\"}";
                byte[] bytes = (json.writeValueAsString(Map.of("message", Map.of("content", content), "done", true))
                        + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) { output.write(bytes); }
                } catch (java.io.IOException e) {
                    if (!stream) { throw e; }
                }
            });
            server.start();
            AppConfig config = new AppConfig("Puppy", "test", true, Path.of(args[0]),
                    "http://127.0.0.1:" + server.getAddress().getPort(), AppConfig.DEFAULT_OLLAMA_MODEL,
                    null, AppConfig.DEFAULT_OPENROUTER_BASE_URL);
            try (ServiceRegistry services = new ServiceRegistry(config)) {
                services.focusService().addSession(FocusService.newManualSession("Math", "Practice", FocusType.QUICK,
                        LocalDate.now().plusDays(1), LocalTime.of(10, 0), 25));
                Shell shell = fx(() -> {
                    FXMLLoader loader = new FXMLLoader(Probe.class.getResource("/fxml/shell.fxml"));
                    Parent root = loader.load();
                    Stage stage = new Stage();
                    stage.setScene(new Scene(root, 336, 252));
                    stage.setAlwaysOnTop(true);
                    stage.getScene().getStylesheets().add(Probe.class.getResource("/css/puppy.css").toExternalForm());
                    MascotController controller = loader.getController();
                    controller.attach(stage, services);
                    stage.show();
                    return new Shell(stage, controller, (Node) loader.getNamespace().get("mascotDock"));
                });
                try {
                    fx(() -> {
                        navigate(shell, 0);
                        Stage study = showing("#startPauseButton");
                        ((Button) study.getScene().lookup("#startPauseButton")).fire();
                        button(study, "Menu").fire();
                        assertTrue(study.isShowing(), "Menu must not hide its originating window");
                        navigate(shell, 1);
                        Stage focus = showing(".focus-panel");
                        assertTrue(study.isShowing());
                        button(focus.getScene().lookup(".focus-session"), "Start").fire();
                        assertEquals(MascotState.STUDYING, services.mascotStateService().getState());
                        ((Button) study.getScene().lookup("#startPauseButton")).fire();
                        assertEquals(MascotState.STUDYING, services.mascotStateService().getState(),
                                "Pausing Study must not interrupt the running Focus timer");
                        ((Button) study.getScene().lookup("#startPauseButton")).fire();
                        dragWindow(study, ".study-header", -40, 20);
                        double x = study.getX();
                        double y = study.getY();
                        button(study, "_").fire();
                        assertFalse(study.isShowing());
                        assertTrue(focus.isShowing());
                        navigate(shell, 0);
                        assertEquals(study, showing("#startPauseButton"));
                        assertEquals(x, study.getX(), 1);
                        assertEquals(y, study.getY(), 1);
                        assertEquals("Pause", ((Button) study.getScene().lookup("#startPauseButton")).getText());
                        navigate(shell, 0);
                        assertEquals(1, Window.getWindows().stream().filter(window -> window.getScene() != null
                                && window.getScene().lookup("#startPauseButton") != null).count());
                        assertEquals(x, study.getX(), 1, "Selecting an open window must not recenter it");
                        navigate(shell, 2);
                        Stage chat = showing(".chat-body");
                        ((Button) chat.getScene().lookup("#expandButton")).fire();
                        double width = chat.getWidth();
                        button(chat, "Menu").fire();
                        assertEquals(width, chat.getWidth(), 1, "Menu must not reset expanded Chat");
                        navigate(shell, 3);
                        ((Button) ((VBox) showing(".more-menu").getScene().lookup(".more-menu")).getChildren().get(2)).fire();
                        Stage settings = showing("#alwaysOnTopCheck");
                        assertTrue(study.isShowing() && focus.isShowing() && chat.isShowing() && settings.isShowing());
                        dragWindow(settings, ".panel-header", -25, 15);
                        double settingsX = settings.getX();
                        double settingsY = settings.getY();
                        navigate(shell, 3);
                        ((Button) ((VBox) showing(".more-menu").getScene().lookup(".more-menu")).getChildren().get(2)).fire();
                        assertEquals(settingsX, settings.getX(), 1);
                        assertEquals(settingsY, settings.getY(), 1);
                        startReply(chat, "Background reply");
                        return null;
                    });
                    assertTrue(entered.get().await(5, TimeUnit.SECONDS));
                    fx(() -> {
                        Stage study = showing("#startPauseButton");
                        Stage focus = showing(".focus-panel");
                        Stage chat = showing(".chat-body");
                        ((Button) study.getScene().lookup("#startPauseButton")).fire();
                        ((Button) focus.getScene().lookup("#timerToggle")).fire();
                        assertEquals(MascotState.THINKING, services.mascotStateService().getState(),
                                "Timer changes must not overwrite active chat feedback");
                        ((Button) study.getScene().lookup("#startPauseButton")).fire();
                        ((Button) focus.getScene().lookup("#timerToggle")).fire();
                        assertEquals(MascotState.THINKING, services.mascotStateService().getState());
                        button(chat, "_").fire();
                        assertFalse(chat.isShowing());
                        assertTrue(((Button) chat.getScene().lookup("#sendButton")).isDisabled(),
                                "Collapse must keep generation running");
                        button(showing("#alwaysOnTopCheck"), "X").fire();
                        assertTrue(study.isShowing() && focus.isShowing());
                        assertTrue(((Button) chat.getScene().lookup("#sendButton")).isDisabled(),
                                "Closing another window must not cancel Chat");
                        return null;
                    });
                    release.get().countDown();
                    await(() -> services.chatHistory().conversations().stream().flatMap(chat -> chat.messages().stream())
                            .anyMatch(message -> message.text().equals("Background reply completed."))
                            && services.mascotStateService().getState() == MascotState.STUDYING);
                    entered.set(new CountDownLatch(1));
                    release.set(new CountDownLatch(1));
                    fx(() -> {
                        navigate(shell, 2);
                        Stage chat = showing(".chat-body");
                        assertEquals("Restore", ((Button) chat.getScene().lookup("#expandButton")).getText());
                        startReply(chat, "Cancel this reply");
                        return null;
                    });
                    assertTrue(entered.get().await(5, TimeUnit.SECONDS));
                    fx(() -> {
                        Stage chat = showing(".chat-body");
                        button(chat, "X").fire();
                        assertFalse(chat.isShowing());
                        assertFalse(((Button) chat.getScene().lookup("#sendButton")).isDisabled(),
                                "Explicit Chat close must cancel pending generation");
                        Stage study = showing("#startPauseButton");
                        Stage focus = showing(".focus-panel");
                        ((Button) study.getScene().lookup("#startPauseButton")).fire();
                        assertEquals(MascotState.STUDYING, services.mascotStateService().getState());
                        button(focus, "X").fire();
                        assertEquals(MascotState.STUDYING, services.mascotStateService().getState(),
                                "A hidden running timer must continue to determine the mascot state");
                        navigate(shell, 1);
                        ((Button) focus.getScene().lookup("#timerToggle")).fire();
                        assertEquals(MascotState.IDLE, services.mascotStateService().getState());
                        return null;
                    });
                    release.get().countDown();
                } finally {
                    release.get().countDown();
                    fx(() -> { shell.controller().close(); shell.stage().close(); return null; });
                    services.chatHistory().flush().join();
                }
            } finally {
                server.stop(0);
                workers.shutdownNow();
                Platform.exit();
            }
        }

        private record Shell(Stage stage, MascotController controller, Node mascot) { }

        private static void navigate(Shell shell, int item) {
            Stage menu = maybeShowing(".main-menu");
            if (menu == null) {
                Event.fireEvent(shell.mascot(), new MouseEvent(MouseEvent.MOUSE_CLICKED,
                        10, 10, 10, 10, MouseButton.PRIMARY, 2,
                        false, false, false, false, false, false, false, false, false, true, null));
                menu = showing(".main-menu");
            }
            ((Button) ((HBox) menu.getScene().lookup(".main-menu")).getChildren().get(item)).fire();
        }

        private static void dragWindow(Stage stage, String headerSelector, double dx, double dy) {
            Node header = stage.getScene().lookup(headerSelector);
            double x = stage.getX();
            double y = stage.getY();
            mouse(header, MouseEvent.MOUSE_PRESSED, x + 20, y + 15, true);
            mouse(header, MouseEvent.MOUSE_DRAGGED, x + 20 + dx, y + 15 + dy, true);
            mouse(header, MouseEvent.MOUSE_RELEASED, x + 20 + dx, y + 15 + dy, false);
            assertEquals(x + dx, stage.getX(), 1);
            assertEquals(y + dy, stage.getY(), 1);
            double movedX = stage.getX();
            double movedY = stage.getY();
            Button menu = button(stage, "Menu");
            mouse(menu, MouseEvent.MOUSE_PRESSED, movedX + 30, movedY + 15, true);
            mouse(menu, MouseEvent.MOUSE_DRAGGED, movedX + 50, movedY + 30, true);
            mouse(menu, MouseEvent.MOUSE_RELEASED, movedX + 50, movedY + 30, false);
            assertEquals(movedX, stage.getX(), 1, "Dragging a header button must not drag the window");
            assertEquals(movedY, stage.getY(), 1);
        }

        private static void mouse(Node target, javafx.event.EventType<MouseEvent> type,
                                  double screenX, double screenY, boolean down) {
            Event.fireEvent(target, new MouseEvent(type, 10, 10, screenX, screenY, MouseButton.PRIMARY, 1,
                    false, false, false, false, down, false, false, false, false, false, null));
        }

        private static void startReply(Stage chat, String request) {
            ((TextField) chat.getScene().lookup("#messageInput")).setText(request);
            ((Button) chat.getScene().lookup("#sendButton")).fire();
        }

        private static Button button(Stage stage, String text) { return button(stage.getScene().getRoot(), text); }

        private static Button button(Node root, String text) {
            return root.lookupAll(".button").stream().map(Button.class::cast)
                    .filter(button -> button.getText().equals(text)).findFirst().orElseThrow();
        }

        private static Stage showing(String selector) {
            Stage stage = maybeShowing(selector);
            assertNotNull(stage, selector);
            return stage;
        }

        private static Stage maybeShowing(String selector) {
            return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                    .filter(Stage::isShowing).filter(stage -> stage.getScene() != null && stage.getScene().lookup(selector) != null)
                    .findFirst().orElse(null);
        }

        private static <T> T fx(Callable<T> work) throws Exception {
            FutureTask<T> task = new FutureTask<>(work);
            Platform.runLater(task);
            return task.get(10, TimeUnit.SECONDS);
        }

        private static void await(BooleanSupplier ready) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!fx(ready::getAsBoolean)) {
                assertTrue(System.nanoTime() < deadline, "Background activity did not finish");
                Thread.sleep(20);
            }
        }
    }
}
