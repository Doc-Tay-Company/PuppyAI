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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenRouterClientTest {

    private static final String TEST_KEY = "sk-or-v1-test-key-1234567890";

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
    void streamsSseTextAndSendsTheExpectedRequest() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        server.createContext("/api/v1/chat/completions", exchange -> {
            requestBody.set(readBody(exchange));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            respondSse(exchange, 200,
                    "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
                    "{\"choices\":[{\"delta\":{\"reasoning\":\"private reasoning\"}}]}",
                    "{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
                    "{\"choices\":[{\"delta\":{\"content\":\"lo\"},"
                            + "\"finish_reason\":\"stop\"}]}",
                    "[DONE]");
        });

        OpenRouterClient client = client();
        List<String> tokens = new ArrayList<>();
        String reply = client.chat(List.of(
                ChatMessage.system("Puppy rules"),
                ChatMessage.user("Say hello"),
                ChatMessage.assistant("Earlier reply")), tokens::add);

        assertEquals("Hello", reply);
        assertEquals(List.of("Hel", "lo"), tokens,
                "Reasoning fragments must not be shown in the chat bubble");
        assertEquals("Bearer " + TEST_KEY, authorization.get(),
                "The key must travel in the Authorization header");
        assertEquals("text/event-stream", accept.get());
        String body = requestBody.get();
        assertTrue(body.contains("\"model\":\"google/gemini-test\""));
        assertTrue(body.contains("\"stream\":true"));
        assertTrue(body.contains("\"max_tokens\":4096"));
        assertTrue(body.contains("\"role\":\"system\""),
                "Puppy's rules should be sent as a system message");
        assertTrue(body.contains("\"role\":\"assistant\""));
        assertTrue(body.contains("\"role\":\"user\""));
        assertFalse(body.contains(TEST_KEY), "The key must never be in the request body");
    }

    @Test
    void jsonChatUsesJsonModeAndReturnsTheWholeObject() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/v1/chat/completions", exchange -> {
            requestBody.set(readBody(exchange));
            respondJson(exchange, 200,
                    "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"{\\\"tasks\\\":[]}\"},"
                            + "\"finish_reason\":\"stop\"}]}");
        });

        String reply = client().jsonChat(List.of(ChatMessage.user("Extract the timetable")));

        assertEquals("{\"tasks\":[]}", reply);
        assertTrue(requestBody.get().contains(
                        "\"response_format\":{\"type\":\"json_object\"}"),
                "Focus extraction must ask OpenRouter for JSON output");
        assertTrue(requestBody.get().contains("\"stream\":false"));
        assertTrue(requestBody.get().contains("\"temperature\":0.1"),
                "JSON extraction should use a low temperature");
    }

    @Test
    void jsonFallbackForModelsWithoutJsonModeAsksInThePrompt() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/v1/chat/completions", exchange -> {
            requestBody.set(readBody(exchange));
            respondJson(exchange, 200,
                    "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"{\\\"tasks\\\":[]}\"},\"finish_reason\":\"stop\"}]}");
        });

        OpenRouterClient client = new OpenRouterClient(
                TEST_KEY, "acme/no-json-mode", baseUrl(), false);
        String reply = client.jsonChat(List.of(
                ChatMessage.system("Puppy rules"),
                ChatMessage.user("Extract the timetable")));

        assertEquals("{\"tasks\":[]}", reply);
        String body = requestBody.get();
        assertFalse(body.contains("\"response_format\""),
                "Models without JSON mode must not receive response_format");
        assertTrue(body.contains("Return only one valid JSON object"),
                "The fallback must ask for JSON in the prompt");
        assertTrue(body.contains("\"role\":\"system\""),
                "The instruction must be a system message");
    }

    @Test
    void retriesWithoutJsonModeWhenTheProviderRejectsIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> secondBody = new AtomicReference<>();
        server.createContext("/api/v1/chat/completions", exchange -> {
            String body = readBody(exchange);
            if (calls.getAndIncrement() == 0) {
                respondJson(exchange, 400,
                        "{\"error\":{\"message\":\"response_format is not supported\","
                                + "\"code\":400}}");
                return;
            }
            secondBody.set(body);
            respondJson(exchange, 200,
                    "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"{\\\"tasks\\\":[]}\"},\"finish_reason\":\"stop\"}]}");
        });

        String reply = client().jsonChat(List.of(ChatMessage.user("Extract the timetable")));

        assertEquals("{\"tasks\":[]}", reply);
        assertEquals(2, calls.get(), "Puppy should retry once without JSON mode");
        assertFalse(secondBody.get().contains("\"response_format\""));
        assertTrue(secondBody.get().contains("Return only one valid JSON object"));
    }

    @Test
    void reportsInvalidKeysWithoutEchoingThem() throws Exception {        server.createContext("/api/v1/chat/completions", exchange ->
                respondJson(exchange, 401,
                        "{\"error\":{\"message\":\"Invalid API key: " + TEST_KEY
                                + "\",\"code\":401}}"));

        AiException error = assertThrows(AiException.class,
                () -> client().chat(List.of(ChatMessage.user("hi")), token -> { }));

        assertEquals(AiException.Reason.AUTHENTICATION, error.reason());
        assertTrue(error.getMessage().contains("Settings"));
        assertFalse(error.getMessage().contains(TEST_KEY),
                "Errors must not leak the API key");
    }

    @Test
    void reportsRateLimitsFriendly() throws Exception {
        server.createContext("/api/v1/chat/completions", exchange ->
                respondJson(exchange, 429,
                        "{\"error\":{\"message\":\"Rate limit exceeded\",\"code\":429}}"));

        AiException error = assertThrows(AiException.class,
                () -> client().chat(List.of(ChatMessage.user("hi")), token -> { }));

        assertEquals(AiException.Reason.RATE_LIMIT, error.reason());
        assertTrue(error.getMessage().contains("OpenRouter"));
    }

    @Test
    void reportsSafetyStopsFriendly() throws Exception {
        server.createContext("/api/v1/chat/completions", exchange ->
                respondSse(exchange, 200,
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}"));

        AiException error = assertThrows(AiException.class,
                () -> client().chat(List.of(ChatMessage.user("hi")), token -> { }));

        assertEquals(AiException.Reason.PROTOCOL, error.reason());
        assertTrue(error.getMessage().contains("safety"));
    }

    @Test
    void reportsUnreachableCloudHostsFriendly() {
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;

        OpenRouterClient client = new OpenRouterClient(
                TEST_KEY, "google/gemini-test", "http://127.0.0.1:" + port + "/api/v1");
        AiException error = assertThrows(AiException.class,
                () -> client.chat(List.of(ChatMessage.user("hi")), token -> { }));

        assertEquals(AiException.Reason.UNAVAILABLE, error.reason());
        assertTrue(error.getMessage().contains("OpenRouter"));
        assertFalse(error.getMessage().contains(TEST_KEY));
    }

    private OpenRouterClient client() {
        return new OpenRouterClient(TEST_KEY, "google/gemini-test", baseUrl());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respondSse(HttpExchange exchange, int status, String... entries)
            throws IOException {
        StringBuilder body = new StringBuilder();
        for (String entry : entries) {
            body.append("data: ").append(entry).append("\n\n");
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
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
