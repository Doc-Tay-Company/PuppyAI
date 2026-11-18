package com.puppy.service;

import com.puppy.ai.AiException;
import com.puppy.ai.AiModel;
import com.puppy.ai.AiModels;
import com.puppy.ai.ChatProvider;
import com.puppy.ai.OllamaClient;
import com.puppy.ai.OpenRouterClient;
import com.puppy.config.AiSettingsStore;
import com.puppy.config.AppConfig;
import com.puppy.model.ChatMessage;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Selects the active AI model and routes requests to it.
 *
 * <p>OpenRouter models are always offered so the Settings panel can collect a
 * key after the user selects one. With no explicit saved choice Puppy prefers
 * OpenRouter for faster replies when a key exists and otherwise uses the local
 * Ollama model. A choice made in Settings is persisted and wins on later
 * launches.</p>
 *
 * <p>Requests capture the current provider, so switching models affects the
 * next reply without disturbing one already streaming.</p>
 */
public final class ModelService {

    private final AppConfig config;
    private final AiSettingsStore store;
    private final Object lock = new Object();

    private List<AiModel> availableModels;
    private AiModel selectedModel;
    private ChatProvider provider;
    private String openRouterKey;
    private boolean openRouterKeyFromEnvironment;

    public ModelService(AppConfig config, AiSettingsStore store) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        synchronized (lock) {
            refreshLocked();
        }
    }

    public AiModel selectedModel() {
        synchronized (lock) {
            return selectedModel;
        }
    }

    /**
     * All catalog models, cloud and local. Cloud entries stay visible without a
     * key so Settings can show the OpenRouter key field for the chosen model.
     */
    public List<AiModel> availableModels() {
        synchronized (lock) {
            return availableModels;
        }
    }

    public boolean hasOpenRouterKey() {
        synchronized (lock) {
            return openRouterKey != null;
        }
    }

    public boolean isOpenRouterKeyFromEnvironment() {
        synchronized (lock) {
            return openRouterKeyFromEnvironment;
        }
    }

    public boolean canEditOpenRouterKey() {
        synchronized (lock) {
            return !openRouterKeyFromEnvironment;
        }
    }

    public boolean isCloud() {
        synchronized (lock) {
            return selectedModel.cloud();
        }
    }

    public String modelName() {
        synchronized (lock) {
            return selectedModel.modelName();
        }
    }

    public String selectedModelLabel() {
        synchronized (lock) {
            return selectedModel.displayName();
        }
    }

    public String providerName() {
        synchronized (lock) {
            return selectedModel.providerName();
        }
    }

    public String statusLabel() {
        synchronized (lock) {
            return selectedModel.displayName() + " · " + selectedModel.locationLabel();
        }
    }

    public String describeSelectedModel() {
        synchronized (lock) {
            return selectedModel.describe();
        }
    }

    public String localModelDescription() {
        return AiModels.friendlyModelName(config.ollamaModel())
                + " (" + config.ollamaModel() + ")";
    }

    public String localServer() {
        return config.ollamaUrl();
    }

    public String connectionLabel() {
        synchronized (lock) {
            return selectedModel.cloud()
                    ? selectedModel.providerName() + " cloud"
                    : config.ollamaUrl();
        }
    }

    /**
     * Persists a model chosen in Settings. A cloud model can be selected before
     * its key exists so the Settings panel can collect the key next.
     */
    public void selectModel(String modelId) throws IOException {
        Objects.requireNonNull(modelId, "modelId");
        synchronized (lock) {
            AiModel model = availableModels.stream()
                    .filter(candidate -> candidate.id().equals(modelId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "That model isn't in Puppy's model list."));
            store.saveSelectedModelId(model.id());
            refreshLocked();
        }
    }

    public void saveOpenRouterKey(String apiKey) throws IOException {
        synchronized (lock) {
            if (openRouterKeyFromEnvironment) {
                throw new IllegalStateException(
                        "The OpenRouter key comes from your environment and can't be changed here.");
            }
            store.saveOpenRouterApiKey(apiKey);
            refreshLocked();
        }
    }

    public void clearOpenRouterKey() throws IOException {
        synchronized (lock) {
            if (openRouterKeyFromEnvironment) {
                throw new IllegalStateException(
                        "The OpenRouter key comes from your environment and can't be removed here.");
            }
            AiModel current = selectedModel;
            // Keep a cloud model selected so Settings can collect a new key. A
            // model that was only the startup default has no saved id yet.
            if (current.cloud() && store.selectedModelId().isEmpty()) {
                store.saveSelectedModelId(current.id());
            }
            store.clearOpenRouterApiKey();
            refreshLocked();
        }
    }

    public String streamChat(List<ChatMessage> messages, Consumer<String> onToken)
            throws AiException {
        return currentProvider().chat(messages, onToken);
    }

    public String jsonChat(List<ChatMessage> messages) throws AiException {
        return currentProvider().jsonChat(messages);
    }

    private ChatProvider currentProvider() throws AiException {
        synchronized (lock) {
            if (provider == null) {
                throw new AiException(
                        AiException.Reason.AUTHENTICATION,
                        "Add your OpenRouter API key in More > Settings to use "
                                + selectedModel.displayName() + ".");
            }
            return provider;
        }
    }

    private void refreshLocked() {
        String environmentKey = config.hasOpenRouterApiKey() ? config.openRouterApiKey() : null;
        openRouterKeyFromEnvironment = environmentKey != null;
        openRouterKey = environmentKey != null
                ? environmentKey
                : store.storedOpenRouterApiKey().orElse(null);

        List<AiModel> catalog = AiModels.catalog(config.ollamaModel());
        availableModels = catalog;

        String savedId = store.selectedModelId().orElse(null);
        AiModel chosen = availableModels.stream()
                .filter(model -> model.id().equals(savedId))
                .findFirst()
                .orElseGet(this::defaultModelLocked);
        selectedModel = chosen;
        provider = createProviderLocked(chosen);
    }

    /** Cloud-first default when a key exists, local otherwise. */
    private AiModel defaultModelLocked() {
        if (openRouterKey != null) {
            return availableModels.stream()
                    .filter(AiModel::cloud)
                    .findFirst()
                    .orElseGet(this::localModelLocked);
        }
        return localModelLocked();
    }

    private AiModel localModelLocked() {
        return availableModels.stream()
                .filter(model -> !model.cloud())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Puppy has no local model configured."));
    }

    private ChatProvider createProviderLocked(AiModel model) {
        if (AiModels.OPENROUTER_PROVIDER.equals(model.providerId())) {
            if (openRouterKey == null) {
                return null;
            }
            return new OpenRouterClient(
                    openRouterKey, model.modelName(), config.openRouterBaseUrl());
        }
        return new OllamaClient(config.ollamaUrl(), model.modelName());
    }
}
