package com.puppy.service;

import com.puppy.ai.OllamaClient;
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
    void buildsAFriendlyLabelFromTheRegistryModelName() {
        ChatService service = service();

        assertEquals("Qwen3.5 9B", service.localModelLabel());
        assertTrue(service.greeting().contains("/model"),
                "The new-chat greeting should point at the /model command");
        assertFalse(service.greeting().contains("Qwen3.5 9B"),
                "The new-chat greeting should not name the model");
        assertTrue(service.describeLocalModel().contains("hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M"));
    }

    @Test
    void readsAttachedDocumentsThroughTheService() throws Exception {
        Path file = tempDirectory.resolve("notes.txt");
        Files.writeString(file, "Puppy can read this note.");

        DocumentAttachment attachment = service().readDocument(file);

        assertTrue(attachment.text().contains("Puppy can read this note."));
    }

    private ChatService service() {
        return new ChatService(
                new OllamaClient("http://localhost:11434",
                        "hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M"),
                new DocumentTextExtractor());
    }
}
