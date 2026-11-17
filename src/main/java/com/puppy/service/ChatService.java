package com.puppy.service;

import com.puppy.ai.AiException;
import com.puppy.document.DocumentExtractionException;
import com.puppy.document.DocumentTextExtractor;
import com.puppy.model.ChatMessage;
import com.puppy.model.DocumentAttachment;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Application service for chat and document attachments.
 *
 * <p>Controllers call this class; they never talk to Ollama, OpenRouter or the
 * PDF/Word libraries directly. The active model comes from
 * {@link ModelService}, so switching models in Settings needs no controller
 * changes. Replies stream through {@code onToken} on a worker thread, so the
 * caller is responsible for marshalling UI updates.</p>
 */
public final class ChatService {

    private final ModelService modelService;
    private final DocumentTextExtractor documentTextExtractor;

    public ChatService(ModelService modelService, DocumentTextExtractor documentTextExtractor) {
        this.modelService = Objects.requireNonNull(modelService, "modelService");
        this.documentTextExtractor = Objects.requireNonNull(documentTextExtractor, "documentTextExtractor");
    }

    /** Streams a reply using the recent conversation and any attached documents. */
    public String reply(List<ChatMessage> conversation, List<DocumentAttachment> attachments,
                        Consumer<String> onToken) throws AiException {
        Objects.requireNonNull(onToken, "onToken");
        return modelService.streamChat(ChatPromptBuilder.build(conversation, attachments), onToken);
    }

    /** Extracts readable text from a local file. Safe to call from a worker thread. */
    public DocumentAttachment readDocument(Path file) throws DocumentExtractionException {
        return documentTextExtractor.extract(file);
    }

    public String greeting() {
        return "Hi! I'm Puppy, your desktop companion. Ask me anything.";
    }

    public String modelName() {
        return modelService.modelName();
    }

    public String modelLabel() {
        return modelService.selectedModelLabel();
    }

    public String providerName() {
        return modelService.providerName();
    }

    public String connectionLabel() {
        return modelService.connectionLabel();
    }

    public boolean isCloud() {
        return modelService.isCloud();
    }

    public String statusLabel() {
        return modelService.statusLabel();
    }

    /** Short description used by Settings; never includes credentials. */
    public String describeSelectedModel() {
        return modelService.describeSelectedModel();
    }
}
