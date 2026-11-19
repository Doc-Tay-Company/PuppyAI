package com.puppy.controller;

import com.puppy.ai.AiException;
import com.puppy.model.StoredMessage;
import com.puppy.service.DefaultMascotStateService;
import com.puppy.service.IntentResolutionService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChatRoutingStreamingTest {
    @TempDir Path directory;

    @Test
    void technicalMessagesIdentifyRateLimitAndProtocolWithoutBlamingUser() {
        assertTrue(ChatController.routingFailureMessage(AiException.Reason.RATE_LIMIT).contains("limiting requests"));
        assertTrue(ChatController.routingFailureMessage(AiException.Reason.PROTOCOL).contains("response Puppy couldn't read"));
        for (AiException.Reason reason : AiException.Reason.values()) {
            String message = ChatController.routingFailureMessage(reason);
            assertFalse(message.isBlank());
            assertFalse(message.contains("rephrase"));
            assertFalse(message.contains("understand"));
        }
    }

    @Test
    void clarificationFailureProgressiveChunksAndStaleCallbacks() throws Exception {
        Path log = directory.resolve("chat-ui.log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Djavafx.cachedir=" + directory.resolve("javafx-cache"), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Chat UI probe timed out: " + Files.readString(log));
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    /** Injected chunks/decisions exercise real controller UI methods, not a real provider or model. */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Platform.startup(() -> { });
            Platform.setImplicitExit(false);
            try {
                ChatController clarify = fx(() -> fixture());
                fx(() -> {
                    var decision = new IntentResolutionService.Decision(IntentResolutionService.Outcome.CLARIFY,
                            "Bạn muốn trộn những chủ đề nào?", null, null, List.of(), List.of(), List.of(),
                            false, false, false);
                    invoke(clarify, "completeRouting", 1, get(clarify, "currentConversationId"), "placeholder",
                            "Mix everything!", List.of(), List.of(), List.of(), "", decision);
                    assertEquals(StoredMessage.Status.COMPLETE, messageValue(clarify, "status"));
                    assertEquals(decision.reply(), messageValue(clarify, "text"));
                    assertFalse(visibleText(clarify).contains("Reply failed"));
                    return null;
                });
                for (var reason : List.of(AiException.Reason.RATE_LIMIT, AiException.Reason.PROTOCOL)) {
                    ChatController failure = fx(() -> fixture());
                    fx(() -> {
                        String error = ChatController.routingFailureMessage(reason);
                        invoke(failure, "failRouting", 1, get(failure, "currentConversationId"), "placeholder", error);
                        assertEquals(StoredMessage.Status.ERROR, messageValue(failure, "status"));
                        assertEquals(error, messageValue(failure, "text"));
                        assertTrue(visibleText(failure).contains("Reply failed"));
                        return null;
                    });
                }

                ChatController stream = fx(() -> fixture());
                // Enqueue on the worker/test thread. A following FX barrier observes a flush before completion.
                invoke(stream, "enqueueToken", 1, "First ");
                fx(() -> {
                    assertEquals("First ", ((Label) get(stream, "streamingLabel")).getText());
                    assertEquals(StoredMessage.Status.GENERATING, messageValue(stream, "status"));
                    return null;
                });
                invoke(stream, "enqueueToken", 1, "second");
                fx(() -> {
                    assertEquals("First second", ((Label) get(stream, "streamingLabel")).getText());
                    invoke(stream, "completeReply", 1, "First second", 100L);
                    assertEquals(StoredMessage.Status.COMPLETE, messageValue(stream, "status"));
                    return null;
                });

                ChatController partial = fx(() -> fixture());
                fx(() -> {
                    // Fail before the queued flush runs: buffered partial must survive.
                    invoke(partial, "enqueueToken", 1, "Partial answer");
                    invoke(partial, "failReply", 1, "Connection interrupted.");
                    assertEquals("Partial answer", messageValue(partial, "text"));
                    assertEquals(StoredMessage.Status.ERROR, messageValue(partial, "status"));
                    return null;
                });

                for (boolean switchConversation : List.of(false, true)) {
                    ChatController stale = fx(() -> fixture());
                    fx(() -> {
                        invoke(stale, "enqueueToken", 1, "Old chunk");
                        invoke(stale, switchConversation ? "newConversation" : "cancelResponse");
                        return null;
                    });
                    invoke(stale, "enqueueToken", 1, "STALE");
                    fx(() -> {
                        invoke(stale, "completeReply", 1, "STALE", 1L);
                        invoke(stale, "failRouting", 1, get(stale, "currentConversationId"), "placeholder", "STALE");
                        assertFalse(visibleText(stale).contains("STALE"));
                        assertTrue(((StringBuilder) get(stale, "pendingTokens")).isEmpty());
                        return null;
                    });
                }
                try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-19T05:21:01Z"),
                            java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
                    var today = java.time.LocalDate.of(2026, 11, 19);
                    var focus = new com.puppy.service.FocusService(documents ->
                            new com.puppy.model.FocusExtraction(new com.puppy.model.FocusPlannerInput(List.of(),
                                    List.of(new com.puppy.model.StudyTask("Math", "Review",
                                            com.puppy.enums.FocusType.QUICK, 25, today,
                                            com.puppy.enums.Priority.MEDIUM))), List.of()),
                            new com.puppy.document.DocumentTextExtractor(), clock);
                    focus.setPreferences(new com.puppy.model.FocusPreferences(
                            java.time.LocalTime.of(10, 0), java.time.LocalTime.of(21, 0)));
                    ChatController defaultNow = fx(() -> {
                        ChatController result = fixture();
                        set(result, "responding", false);
                        set(result, "focusService", focus);
                        set(result, "executor", worker);
                        set(result, "focusOpenAction", (Runnable) () -> { });
                        invoke(result, "startFocus", "Math is important today");
                        assertTrue((boolean) get(result, "responding"));
                        assertFalse(visibleText(result).contains("When are you free"));
                        return result;
                    });
                    worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
                    fx(() -> {
                        assertFalse((boolean) get(defaultNow, "responding"));
                        assertEquals(java.time.LocalTime.of(12, 25),
                                focus.currentPlan().sessions().getFirst().start());
                        assertEquals(java.time.LocalTime.of(10, 0), focus.preferences().earliest(),
                                "The default must preserve saved availability for future days");
                        invoke(defaultNow, "startFocus", "Math today 18:00-21:00");
                        return null;
                    });
                    worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
                    fx(() -> {
                        assertFalse((boolean) get(defaultNow, "responding"));
                        assertEquals(java.time.LocalTime.of(18, 0),
                                focus.currentPlan().sessions().getFirst().start());
                        assertEquals(java.time.LocalTime.of(18, 0), focus.preferences().earliest());
                        return null;
                    });
                }
                System.out.println("PASS: clarification, technical failures, progressive chunks, partial and stale guards");
            } finally {
                Platform.exit();
            }
        }

        private static ChatController fixture() throws Exception {
            FXMLLoader loader = new FXMLLoader(Probe.class.getResource("/fxml/chat.fxml"));
            loader.load();
            ChatController controller = loader.getController();
            set(controller, "mascotStateService", new DefaultMascotStateService());
            set(controller, "talkingTransition", new PauseTransition());
            set(controller, "happyTransition", new PauseTransition());
            set(controller, "restoring", true); // No history/database/backend is attached.
            set(controller, "responding", true);
            set(controller, "responseGeneration", 1);
            set(controller, "activeRouteMessageId", "placeholder");
            set(controller, "streamingMessageIndex", 0);
            Class<?> type = Class.forName(ChatController.class.getName() + "$Message");
            var constructor = type.getDeclaredConstructor(String.class, String.class, String.class, List.class,
                    long.class, StoredMessage.Status.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked") List<Object> messages = (List<Object>) get(controller, "currentMessages");
            messages.add(constructor.newInstance("placeholder", "assistant", "", List.of(), 0L,
                    StoredMessage.Status.GENERATING));
            return controller;
        }

        private static Object messageValue(ChatController controller, String name) throws Exception {
            Object message = ((List<?>) get(controller, "currentMessages")).getFirst();
            var method = message.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(message);
        }

        private static String visibleText(ChatController controller) throws Exception {
            return labels((VBox) get(controller, "messagesBox"));
        }

        private static String labels(javafx.scene.Node node) {
            String text = node instanceof Label label ? label.getText() : "";
            if (node instanceof javafx.scene.Parent parent) {
                for (var child : parent.getChildrenUnmodifiable()) text += labels(child);
            }
            return text;
        }

        private static Object get(Object target, String name) throws Exception {
            var field = ChatController.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }

        private static void set(Object target, String name, Object value) throws Exception {
            var field = ChatController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }

        private static void invoke(Object target, String name, Object... args) throws Exception {
            for (var method : ChatController.class.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    method.setAccessible(true);
                    method.invoke(target, args);
                    return;
                }
            }
            throw new NoSuchMethodException(name);
        }

        private static <T> T fx(Callable<T> work) throws Exception {
            FutureTask<T> task = new FutureTask<>(work);
            Platform.runLater(task);
            return task.get(15, TimeUnit.SECONDS);
        }
    }
}
