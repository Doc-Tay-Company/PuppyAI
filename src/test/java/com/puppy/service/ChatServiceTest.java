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
