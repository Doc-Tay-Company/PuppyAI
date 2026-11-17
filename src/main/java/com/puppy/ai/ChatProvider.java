package com.puppy.ai;

import com.puppy.model.ChatMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * A model backend that can answer a conversation.
 *
 * <p>Both the local Ollama client and the cloud OpenRouter client implement this
 * contract. Services use the selected provider; they never branch on a
 * provider name or reach into a concrete client.</p>
 */
public interface ChatProvider {

    String providerId();

    String providerName();

    String model();

    boolean isCloud();

    /** URL for a local server, or the provider host for a cloud model. */
    String serverLabel();

    /** Quick readiness check; cloud providers treat a configured key as ready. */
    boolean isAvailable();

    /** True when the configured model should be usable for this provider. */
    boolean hasModel();

    /** Streams the assistant reply and forwards each content fragment. */
    String chat(List<ChatMessage> messages, Consumer<String> onToken) throws AiException;

    /** Sends a request that must return one JSON object. */
    String jsonChat(List<ChatMessage> messages) throws AiException;
}
