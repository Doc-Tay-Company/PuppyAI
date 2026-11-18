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
                    new DocumentTextExtractor());
            assertEquals("Review algebra basics", service.conversationTitle("Help me understand algebra"));
            assertTrue(request.get().contains("Help me understand algebra"));
            assertTrue(request.get().contains("\"format\":\"json\""));
            reply.set("{\"title\":\"" + "x".repeat(81) + "\"}");
            assertThrows(com.puppy.ai.AiException.class, () -> service.conversationTitle("test"));
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
