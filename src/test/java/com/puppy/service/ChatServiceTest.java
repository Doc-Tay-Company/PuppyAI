package com.puppy.service;

import com.puppy.config.AiSettingsStore;
import com.puppy.config.AppConfig;
import com.puppy.document.DocumentTextExtractor;
import com.puppy.model.DocumentAttachment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void buildsAFriendlyLabelFromTheConfiguredLocalModel() {
        ChatService service = service();

        assertEquals("Qwen3.5 9B", service.modelLabel());
        assertEquals("Hi! I'm Puppy, your desktop companion. Ask me anything.",
                service.greeting(),
                "The new-chat greeting should end at Ask me anything");
        assertFalse(service.greeting().contains("Qwen3.5 9B"),
                "The new-chat greeting should not name the model");
        assertTrue(service.describeSelectedModel().contains("hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M"));
        assertFalse(service.isCloud());
    }

    @Test
    void readsAttachedDocumentsThroughTheService() throws Exception {
        Path file = tempDirectory.resolve("notes.txt");
        Files.writeString(file, "Puppy can read this note.");

        DocumentAttachment attachment = service().readDocument(file);

        assertTrue(attachment.text().contains("Puppy can read this note."));
    }

    @Test
    void generatesShortTitlesThroughSelectedProviderAndRejectsInvalidOutput() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var reply = new java.util.concurrent.atomic.AtomicReference<>("{\"title\":\"Review algebra basics\"}");
        var request = new java.util.concurrent.atomic.AtomicReference<String>();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        server.createContext("/api/chat", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] bytes = json.writeValueAsBytes(java.util.Map.of("message", java.util.Map.of("content", reply.get())));
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            AppConfig config = new AppConfig("Puppy", "test", true, tempDirectory,
                    "http://127.0.0.1:" + server.getAddress().getPort(), AppConfig.DEFAULT_OLLAMA_MODEL,
                    null, AppConfig.DEFAULT_OPENROUTER_BASE_URL);
            var service = new ChatService(new ModelService(config, new AiSettingsStore(tempDirectory)),
                    new DocumentTextExtractor(), () -> "This preference must not reach title generation.");
            assertEquals("Review algebra basics", service.conversationTitle("Help me understand algebra"));
            assertTrue(request.get().contains("Help me understand algebra"));
            assertFalse(request.get().contains("This preference must not reach title generation."));
            assertTrue(request.get().contains("\"format\":\"json\""));
            reply.set("{\"title\":\"" + "x".repeat(81) + "\"}");
            assertThrows(com.puppy.ai.AiException.class, () -> service.conversationTitle("test"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void capturesSavedInstructionsForEachRequestAcrossLocalAndCloudProviders() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        server.createContext("/", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            boolean local = exchange.getRequestURI().getPath().equals("/api/chat");
            String response = local ? "{\"message\":{\"content\":\"Hello\"},\"done\":true}\n"
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n";
            exchange.getResponseHeaders().set("Content-Type", local ? "application/x-ndjson" : "text/event-stream");
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            AppConfig config = new AppConfig("Puppy", "test", true, tempDirectory, url,
                    AppConfig.DEFAULT_OLLAMA_MODEL, "test-only-openrouter-key-1234567890", url);
            var models = new ModelService(config, new AiSettingsStore(tempDirectory));
            var preferences = new java.util.concurrent.atomic.AtomicReference<>("First saved preference");
            var service = new ChatService(models, new DocumentTextExtractor(), preferences::get);
            var conversation = java.util.List.of(com.puppy.model.ChatMessage.user("Explain this."));
            var prepared = service.prepareReply(conversation, java.util.List.of());
            preferences.set("Updated saved preference");
            models.selectModel(com.puppy.ai.AiModels.localModelId(AppConfig.DEFAULT_OLLAMA_MODEL));
            assertEquals("Hello", service.reply(prepared, token -> { }));
            assertTrue(requests.get(0).path("messages").get(1).path("content").asText().contains("First saved preference"));
            assertFalse(requests.get(0).toString().contains("Updated saved preference"));

            service.reply(conversation, java.util.List.of(), token -> { });
            models.selectModel(com.puppy.ai.AiModels.OPENROUTER_GEMINI_FLASH);
            service.reply(conversation, java.util.List.of(), token -> { });
            assertEquals(requests.get(1).path("messages"), requests.get(2).path("messages"),
                    "Both providers must receive the same saved instructions and fixed persona");
            assertTrue(requests.get(2).toString().contains("Updated saved preference"));
            preferences.set("");
            service.reply(conversation, java.util.List.of(), token -> { });
            assertEquals(ChatPromptBuilder.SYSTEM_PROMPT,
                    requests.get(3).path("messages").get(0).path("content").asText());
        } finally {
            server.stop(0);
        }
    }

    private ChatService service() {
        AppConfig config = new AppConfig(
                "Puppy AI",
                "test",
                true,
                tempDirectory,
                "http://localhost:11434",
                "hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M",
                null,
                AppConfig.DEFAULT_OPENROUTER_BASE_URL);
        return new ChatService(
                new ModelService(config, new AiSettingsStore(tempDirectory)),
                new DocumentTextExtractor());
    }
}
