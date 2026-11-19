package com.puppy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppy.config.AppConfig;
import com.puppy.config.ServiceRegistry;
import com.puppy.model.QuizAttempt;
import com.puppy.model.QuizDefinition;
import com.puppy.ui.QuizCard;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class QuizInteractionTest {
    @TempDir Path directory;

    @Test
    void cardsGenerationFailureCancellationAndRestartWorkTogether() throws Exception {
        Path log = directory.resolve("quiz-ui.log");
        String java = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", java).toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(), directory.resolve("data").toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Quiz UI probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
        }
    }

    public static final class Probe {
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final AtomicInteger quizCalls = new AtomicInteger();
        private static final AtomicBoolean invalid = new AtomicBoolean();
        private static final AtomicBoolean block = new AtomicBoolean();
        private static final CountDownLatch entered = new CountDownLatch(1);
        private static final CountDownLatch release = new CountDownLatch(1);

        public static void main(String[] args) throws Exception {
            Platform.startup(() -> { });
            Platform.setImplicitExit(false);
            HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            var httpWorkers = Executors.newCachedThreadPool();
            server.setExecutor(httpWorkers);
            server.createContext("/api/chat", exchange -> {
                var request = JSON.readTree(exchange.getRequestBody());
                String system = request.path("messages").get(0).path("content").asText();
                String reply;
                if (request.path("stream").asBoolean() && !"json".equals(request.path("format").asText())) {
                    reply = "A streamed reply.";
                } else if (system.contains("You are the intent router")) {
                    var routingInput = JSON.readTree(request.path("messages").get(1).path("content").asText());
                    if (routingInput.path("currentMessage").asText().equals("Hello!")) {
                        reply = "{\"intent\":\"CHAT\",\"reply\":\"\",\"topic\":null,\"count\":null,"
                                + "\"difficulties\":null,\"requiredSourceIds\":[],\"relevantHistoryIndexes\":[],"
                                + "\"sourceRequired\":false,\"documentRequired\":false,\"allowGeneralKnowledge\":false}";
                    } else {
                    reply = "{\"intent\":\"GENERATE_QUIZ\",\"reply\":\"\",\"topic\":\"capitals\","
                            + "\"count\":2,\"difficulties\":[\"EASY\",\"EASY\"],"
                            + "\"requiredSourceIds\":[],\"relevantHistoryIndexes\":[],"
                            + "\"sourceRequired\":false,\"documentRequired\":false,\"allowGeneralKnowledge\":true}";
                    }
                } else if (system.contains("Paraphrase the user's request")) {
                    reply = "{\"title\":\"Capital practice\"}";
                } else {
                    int call = quizCalls.incrementAndGet();
                    if (block.get()) {
                        entered.countDown();
                        try { release.await(10, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    reply = invalid.get() ? "not valid JSON" : generated();
                }
                byte[] bytes = JSON.writeValueAsBytes(Map.of("message", Map.of("content", reply), "done", true));
                try {
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) { output.write(bytes); }
                } catch (java.io.IOException e) {
                    if (!block.get()) { throw e; }
                }
            });
            server.start();
            AppConfig config = new AppConfig("Puppy", "test", true, Path.of(args[0]),
                    "http://127.0.0.1:" + server.getAddress().getPort(), AppConfig.DEFAULT_OLLAMA_MODEL,
                    null, AppConfig.DEFAULT_OPENROUTER_BASE_URL);
            try {
                fx(() -> { testCard(); return null; });
                try (ServiceRegistry services = new ServiceRegistry(config)) {
                    Ui ui = fx(() -> open(services));
                    fx(() -> { generate(ui); return null; });
                    await(() -> cards(ui).size() == 1);
                    assertEquals(1, quizCalls.get());
                    fx(() -> {
                        ui.input().setText("Hello!");
                        ui.actions().getItems().getFirst().fire();
                        ui.send().fire();
                        return null;
                    });
                    await(() -> ui.messages().getChildren().stream().anyMatch(node -> text(node, "A streamed reply.")));
                    assertEquals(1, quizCalls.get(), "Quiz preference must not force a greeting into Quiz generation");
                    fx(() -> {
                        QuizCard card = cards(ui).getFirst();
                        option(card, "A.").fire();
                        button(card, "Check answer").fire();
                        button(card, "Next").fire();
                        option(card, "C.").fire();
                        button(card, "Check answer").fire();
                        button(card, "Finish").fire();
                        assertTrue(text(card, "2 / 2"));
                        ui.input().setText("/model");
                        ui.send().fire();
                        assertEquals(1, cards(ui).size(), "Normal chat must leave the independent card intact");
                        return null;
                    });

                    invalid.set(true);
                    fx(() -> { generate(ui); return null; });
                    await(() -> hasButton(latestBlock(ui), "Retry generation"));
                    assertEquals(2, quizCalls.get(), "Invalid output must not be retried automatically");
                    invalid.set(false);
                    fx(() -> { button(latestBlock(ui), "Retry generation").fire(); return null; });
                    await(() -> cards(ui).size() == 2);
                    assertEquals(3, quizCalls.get());
                    fx(() -> { assertTrue(text(cards(ui).getFirst(), "2 / 2")); return null; });

                    sql(config, "CREATE TRIGGER reject_quiz BEFORE INSERT ON quiz_questions BEGIN SELECT RAISE(ABORT,'injected'); END");
                    fx(() -> { generate(ui); return null; });
                    await(() -> hasButton(latestBlock(ui), "Retry save"));
                    int callsBeforeRetry = quizCalls.get();
                    sql(config, "DROP TRIGGER reject_quiz");
                    fx(() -> { button(latestBlock(ui), "Retry save").fire(); return null; });
                    await(() -> cards(ui).size() == 3);
                    assertEquals(callsBeforeRetry, quizCalls.get(), "Retry save must reuse the validated definition");

                    block.set(true);
                    fx(() -> { generate(ui); return null; });
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    fx(() -> { button(ui.root(), "New").fire(); return null; });
                    release.countDown();
                    fx(() -> {
                        assertTrue(cards(ui).isEmpty(), "Cancelled generation must not enter the new chat");
                        ui.controller().showHistory();
                        ((Button) ((VBox) ui.root().lookup("#historyItems")).getChildren().getFirst()).fire();
                        assertEquals(3, cards(ui).size());
                        assertTrue(text(cards(ui).getFirst(), "2 / 2"), "Switching chats must retain in-memory attempts");
                        ui.controller().close();
                        return null;
                    });
                    services.chatHistory().flush().join();
                    assertEquals(3, services.chatHistory().conversations().getFirst().messages().stream()
                            .filter(message -> message.entryType().equals("QUIZ")).count());
                }
                try (ServiceRegistry reopened = new ServiceRegistry(config)) {
                    Ui ui = fx(() -> open(reopened));
                    fx(() -> {
                        assertEquals(3, cards(ui).size());
                        QuizCard first = cards(ui).getFirst();
                        assertTrue(text(first, "Question 1 of 2"));
                        assertTrue(button(first, "Check answer").isDisabled());
                        assertTrue(first.lookupAll(".quiz-option").stream()
                                .map(RadioButton.class::cast).noneMatch(RadioButton::isSelected));
                        ui.controller().close();
                        return null;
                    });
                    reopened.chatHistory().flush().join();
                }
            } finally {
                release.countDown();
                server.stop(0);
                httpWorkers.shutdownNow();
                Platform.exit();
            }
        }

        private static String generated() throws com.fasterxml.jackson.core.JsonProcessingException {
            return JSON.writeValueAsString(Map.of("title", "Capital practice", "questions", List.of(
                    Map.of("text", "What is the capital of France?", "options", List.of("Paris", "London", "Rome", "Madrid"),
                            "correctIndex", 0, "explanation", "Paris is the capital of France.", "difficulty", "EASY"),
                    Map.of("text", "What is the capital of Italy?", "options", List.of("Paris", "London", "Rome", "Madrid"),
                            "correctIndex", 2, "explanation", "Rome is the capital of Italy.", "difficulty", "EASY"))));
        }

        private static void testCard() {
            QuizDefinition definition = new QuizDefinition("q", "m", "Practice", List.of(
                    new QuizDefinition.Question("a", "First?", List.of("One", "Two", "Three", "Four"), 0,
                            "First explanation.", QuizDefinition.Difficulty.EASY),
                    new QuizDefinition.Question("b", "Second?", List.of("One", "Two", "Three", "Four"), 1,
                            "Second explanation.", QuizDefinition.Difficulty.HARD)));
            QuizAttempt attempt = new QuizAttempt(definition);
            AtomicBoolean confirm = new AtomicBoolean(false);
            AtomicInteger focus = new AtomicInteger();
            QuizCard card = new QuizCard(attempt, focus::incrementAndGet, message -> confirm.get());
            assertTrue(button(card, "Check answer").isDisabled());
            option(card, "B.").fire();
            button(card, "Next").fire();
            assertEquals(0, attempt.answeredCount());
            button(card, "Previous").fire();
            assertTrue(option(card, "B.").isSelected());
            option(card, "A.").fire();
            button(card, "Check answer").fire();
            assertTrue(option(card, "A.").isDisabled());
            assertTrue(text(card, "Correct. Correct answer: A."));
            button(card, "Next").fire();
            button(card, "Finish").fire();
            assertFalse(attempt.finished(), "Declining skipped confirmation must retain the attempt");
            confirm.set(true);
            button(card, "Finish").fire();
            assertTrue(text(card, "1 / 2"));
            assertTrue(text(card, "Skipped: 1"));
            button(card, "Back to chat").fire();
            assertEquals(1, focus.get());
            button(card, "Review").fire();
            button(card, "Next").fire();
            assertTrue(text(card, "Skipped (not checked). Correct answer: B."));
            assertTrue(option(card, "B.").isDisabled());
            button(card, "Back to results").fire();
            confirm.set(false);
            button(card, "Retry quiz").fire();
            assertTrue(attempt.finished());
            confirm.set(true);
            button(card, "Retry quiz").fire();
            assertFalse(attempt.finished());
            assertEquals(0, attempt.index());
            assertEquals(-1, attempt.selection(0));
        }

        private record Ui(Parent root, ChatController controller, MenuButton actions,
                          TextField input, Button send, VBox messages) { }

        private static Ui open(ServiceRegistry services) throws Exception {
            FXMLLoader loader = new FXMLLoader(Probe.class.getResource("/fxml/chat.fxml"));
            Parent root = loader.load();
            new Scene(root, 940, 720);
            ChatController controller = loader.getController();
            controller.attach(services.chatService(), services.threadManager().executor(), services.mascotStateService(),
                    services.focusService(), () -> { }, () -> { }, () -> { }, () -> false, () -> { });
            controller.attachHistory(services.chatHistory());
            controller.attachQuiz(services.quizService());
            controller.attachIntentResolution(services.intentResolutionService());
            root.applyCss();
            root.layout();
            MenuButton actions = (MenuButton) loader.getNamespace().get("actionsButton");
            return new Ui(root, controller, actions, (TextField) loader.getNamespace().get("messageInput"),
                    (Button) loader.getNamespace().get("sendButton"), (VBox) loader.getNamespace().get("messagesBox"));
        }

        private static void generate(Ui ui) {
            ui.input().setText("Create 2 easy questions about capitals");
            ui.actions().getItems().getFirst().fire();
            ui.send().fire();
        }

        private static List<QuizCard> cards(Ui ui) {
            return ui.messages().getChildren().stream().map(node -> node.lookup(".quiz-card"))
                    .filter(QuizCard.class::isInstance).map(QuizCard.class::cast).toList();
        }

        private static Node latestBlock(Ui ui) {
            return ui.messages().getChildren().stream().filter(node -> node.getStyleClass().contains("quiz-message"))
                    .reduce((first, second) -> second).orElseThrow();
        }

        private static boolean text(Node root, String value) {
            if (root instanceof Label label && value.equals(label.getText())) return true;
            if (root instanceof javafx.scene.text.Text text && value.equals(text.getText())) return true;
            return root instanceof Parent parent && parent.getChildrenUnmodifiable().stream()
                    .anyMatch(child -> text(child, value));
        }

        private static RadioButton option(Node root, String prefix) {
            return root.lookupAll(".quiz-option").stream().map(RadioButton.class::cast)
                    .filter(choice -> choice.getText().startsWith(prefix)).findFirst().orElseThrow();
        }

        private static boolean hasButton(Node root, String text) {
            return root.lookupAll(".button").stream().map(Button.class::cast).anyMatch(button -> button.getText().equals(text));
        }

        private static Button button(Node root, String text) {
            return root.lookupAll(".button").stream().map(Button.class::cast)
                    .filter(button -> button.getText().equals(text)).findFirst().orElseThrow();
        }

        private static <T> T fx(Callable<T> work) throws Exception {
            FutureTask<T> task = new FutureTask<>(work);
            Platform.runLater(task);
            return task.get(10, TimeUnit.SECONDS);
        }

        private static void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!fx(condition::getAsBoolean)) {
                assertTrue(System.nanoTime() < deadline, "Quiz UI did not reach the expected state");
                Thread.sleep(20);
            }
        }

        private static void sql(AppConfig config, String sql) throws Exception {
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + config.dataDirectory().resolve("puppy.db"));
                 var statement = connection.createStatement()) { statement.execute(sql); }
        }
    }
}
