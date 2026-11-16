package com.puppy.service;

import com.puppy.ai.OllamaClient;
import com.puppy.ai.OllamaException;
import com.puppy.document.DocumentExtractionException;
import com.puppy.document.DocumentTextExtractor;
import com.puppy.model.ChatMessage;
import com.puppy.model.DocumentAttachment;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Application service for local chat and document attachments.
 *
 * <p>Controllers call this class; they never talk to Ollama or to the PDF/Word
 * libraries directly. Replies stream through {@code onToken} on a worker
 * thread, so the caller is responsible for marshalling UI updates.</p>
 */
public final class ChatService {

    private final OllamaClient ollamaClient;
    private final DocumentTextExtractor documentTextExtractor;

    public ChatService(OllamaClient ollamaClient, DocumentTextExtractor documentTextExtractor) {
        this.ollamaClient = Objects.requireNonNull(ollamaClient, "ollamaClient");
        this.documentTextExtractor = Objects.requireNonNull(documentTextExtractor, "documentTextExtractor");
    }

    /** Streams a reply using the recent conversation and any attached documents. */
    public String reply(List<ChatMessage> conversation, List<DocumentAttachment> attachments,
                        Consumer<String> onToken) throws OllamaException {
        Objects.requireNonNull(onToken, "onToken");
        return ollamaClient.chat(ChatPromptBuilder.build(conversation, attachments), onToken);
    }

    /** Extracts readable text from a local file. Safe to call from a worker thread. */
    public DocumentAttachment readDocument(Path file) throws DocumentExtractionException {
        return documentTextExtractor.extract(file);
    }

    public String greeting() {
        return "Hi! I'm Puppy, your desktop companion. Ask me anything, attach a PDF or Word "
                + "document, or type /model to see which local model is running.";
    }

    public String modelName() {
        return ollamaClient.model();
    }

    public String baseUrl() {
        return ollamaClient.baseUrl();
    }

    public String statusLabel() {
        return localModelLabel() + " · local";
    }

    public boolean isAvailable() {
        return ollamaClient.isAvailable();
    }

    public boolean hasModel() {
        return ollamaClient.hasModel();
    }

    /**
     * Turns a registry-style model name into something short enough for the
     * chat header, e.g. {@code hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M} becomes
     * {@code Qwen3.5 9B}.
     */
    public String localModelLabel() {
        String name = modelName();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int colon = name.indexOf(':');
        if (colon > 0) {
            name = name.substring(0, colon);
        }
        name = name.replace("-GGUF", "")
                .replace("-gguf", "")
                .replace('_', ' ')
                .replace('-', ' ')
                .strip();
        return name.isBlank() ? "the local model" : name;
    }

    /** Short description used by Settings; never includes credentials. */
    public String describeLocalModel() {
        return localModelLabel() + " (" + modelName() + ")";
    }
}
