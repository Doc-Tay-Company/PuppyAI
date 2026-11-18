package com.puppy.ai;

import com.puppy.model.ChatMessage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaClientTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamsContentFragmentsAndSendsTheExpectedRequest() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/chat", exchange -> {
            requestBody.set(readBody(exchange));
            respondJsonLines(exchange, 200,
                    "{\"message\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"done\":false}",
                    "{\"message\":{\"role\":\"assistant\",\"content\":\"lo\"},\"done\":false}",
                    "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}");
        });

        OllamaClient client = client();
        List<String> tokens = new ArrayList<>();
        String reply = client.chat(List.of(ChatMessage.user("Say hello")), tokens::add);

        assertEquals("Hello", reply);
        assertEquals(List.of("Hel", "lo"), tokens);
        assertTrue(requestBody.get().contains("\"model\":\"test-model\""));
        assertTrue(requestBody.get().contains("\"stream\":true"));
        assertTrue(requestBody.get().contains("\"think\":false"));
        assertTrue(requestBody.get().contains("\"role\":\"user\""));
        assertTrue(requestBody.get().contains("\"content\":\"Say hello\""));
    }

    @Test
    void jsonChatAsksOllamaForJsonAndReturnsTheWholeObject() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/chat", exchange -> {
            requestBody.set(readBody(exchange));
            respondJsonLines(exchange, 200,
                    "{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"tasks\\\":[]}\"},\"done\":false}",
                    "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}");
        });

        String reply = client().jsonChat(List.of(ChatMessage.user("Extract the timetable")));

        assertEquals("{\"tasks\":[]}", reply);
        assertTrue(requestBody.get().contains("\"format\":\"json\""),
                "Focus extraction must request Ollama's JSON output mode");
        assertTrue(requestBody.get().contains("\"temperature\":0.1"),
                "JSON extraction should use a low temperature");
    }

    @Test
    void reportsMissingModelsWithAPullHint() throws Exception {
        server.createContext("/api/chat", exchange -> respondJsonLines(exchange, 404,
                "{\"error\":\"model 'test-model' not found\"}"));

        AiException error = assertThrows(AiException.class,
                () -> client().chat(List.of(ChatMessage.user("hi")), token -> { }));

        assertEquals(AiException.Reason.MODEL_NOT_FOUND, error.reason());
        assertTrue(error.getMessage().contains("ollama pull test-model"));
    }

    @Test
    void reportsAnErrorSentInsideTheStream() throws Exception {
        server.createContext("/api/chat", exchange -> respondJsonLines(exchange, 200,
                "{\"message\":{\"role\":\"assistant\",\"content\":\"partial\"},\"done\":false}",
                "{\"error\":\"model 'test-model' not found\"}"));

        List<String> tokens = new ArrayList<>();
        AiException error = assertThrows(AiException.class,
                () -> client().chat(List.of(ChatMessage.user("hi")), tokens::add));

        assertEquals(AiException.Reason.MODEL_NOT_FOUND, error.reason());
        assertEquals(List.of("partial"), tokens);
    }

    @Test
    void unwrapsNestedJsonErrorsForTheChatBubble() throws Exception {
        server.createContext("/api/chat", exchange -> respondJsonLines(exchange, 500,
                "{\"error\":\"{\\\"error\\\":{\\\"code\\\":500,\\\"message\\\":\\\"template exploded\\\"}}\"}"));

        AiException error = assertThrows(AiException.class,
                () -> client().chat(List.of(ChatMessage.user("hi")), token -> { }));

        assertEquals(AiException.Reason.HTTP, error.reason());
        assertTrue(error.getMessage().contains("template exploded"));
    }

    @Test
    void reportsUnreachableServersFriendly() {
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;

        OllamaClient client = new OllamaClient("http://127.0.0.1:" + port, "test-model");
        AiException error = assertThrows(AiException.class,
                () -> client.chat(List.of(ChatMessage.user("hi")), token -> { }));

        assertEquals(AiException.Reason.UNAVAILABLE, error.reason());
        assertTrue(error.getMessage().contains("I can't reach Ollama"));
    }

    @Test
    void checksAvailabilityAndInstalledModel() {
        server.createContext("/api/tags", exchange -> respondJson(exchange, 200,
                "{\"models\":[{\"name\":\"test-model:latest\",\"model\":\"test-model:latest\"}]}"));

        OllamaClient client = client();

        assertTrue(client.isAvailable());
        assertTrue(client.hasModel());
        assertFalse(new OllamaClient(baseUrl(), "another-model").hasModel());
    }

    private OllamaClient client() {
        return new OllamaClient(baseUrl(), "test-model");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respondJsonLines(HttpExchange exchange, int status, String... lines)
            throws IOException {
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            body.append(line).append('\n');
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void respondJson(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
