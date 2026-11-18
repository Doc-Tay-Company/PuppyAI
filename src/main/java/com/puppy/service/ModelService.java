package com.puppy.service;

import com.puppy.ai.AiException;
import com.puppy.ai.AiModel;
import com.puppy.ai.AiModelDetails;
import com.puppy.ai.AiModels;
import com.puppy.ai.ChatProvider;
import com.puppy.ai.OllamaClient;
import com.puppy.ai.OpenRouterClient;
import com.puppy.ai.OpenRouterModelCatalog;
import com.puppy.config.AiSettingsStore;
import com.puppy.config.AppConfig;
import com.puppy.model.ChatMessage;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Selects the active AI model and routes requests to it.
 *
 * <p>OpenRouter models are always offered so the Settings panel can collect a
 * key after the user selects one. With no explicit saved choice Puppy prefers
 * OpenRouter for faster replies when a key exists and otherwise uses the local
 * Ollama model. A choice made in Settings is persisted and wins on later
 * launches, even when that model has disappeared from the provider catalog.</p>
 *
 * <p>The full OpenRouter catalog is fetched in the background through the
 * application {@link Executor} (the {@code ThreadManager} pool). Its raw JSON
 * and fetch time are cached in local settings for 24 hours so the model list
 * still works offline. A failed refresh only returns a warning; the previous
 * list and the selected model stay untouched.</p>
 *
 * <p>Requests capture the current provider, so switching models affects the
 * next reply without disturbing one already streaming.</p>
 */
public final class ModelService {

    /** How long the cached OpenRouter catalog stays fresh. */
    public static final Duration CATALOG_TTL = Duration.ofHours(24);

    private final AppConfig config;
    private final AiSettingsStore store;
    private final Executor catalogExecutor;
    private final Object lock = new Object();

    private List<AiModel> availableModels = List.of();
    private List<AiModel> catalogModels = List.of();
    private AiModel selectedModel;
    private ChatProvider provider;
    private String openRouterKey;
    private boolean openRouterKeyFromEnvironment;
    private Optional<Instant> catalogFetchedAt = Optional.empty();
    private List<String> recentModelIds = List.of();
    private String selectionWarning;
    private CompletableFuture<CatalogRefreshResult> catalogRefresh;

    public ModelService(AppConfig config, AiSettingsStore store) {
        this(config, store, ForkJoinPool.commonPool());
    }

    /**
     * @param catalogExecutor runs catalog HTTP work; pass the shared
     *                        {@code ThreadManager} executor from the app
     */
    public ModelService(AppConfig config, AiSettingsStore store, Executor catalogExecutor) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        this.catalogExecutor = catalogExecutor == null
                ? ForkJoinPool.commonPool()
                : catalogExecutor;
        synchronized (lock) {
            catalogModels = loadCachedCatalogLocked();
            catalogFetchedAt = catalogModels.isEmpty()
                    ? Optional.empty()
                    : store.openRouterCatalogFetchedAt();
            recentModelIds = List.copyOf(store.recentModelIds());
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

    /**
     * The short list shown in the combo box: the selected model, the three
     * built-in Gemini suggestions, recent choices and the local model. The full
     * catalog belongs in the browse dialog instead of a 300-row combo box.
     */
    public List<AiModel> modelPickerModels() {
        synchronized (lock) {
            Map<String, AiModel> picked = new LinkedHashMap<>();
            addModel(picked, selectedModel);
            for (String id : AiModels.SUGGESTED_OPENROUTER_IDS) {
                addModel(picked, findModelLocked(id));
            }
            for (String id : recentModelIds) {
                addModel(picked, findModelLocked(id));
            }
            for (AiModel model : availableModels) {
                if (!model.cloud()) {
                    addModel(picked, model);
                    break;
                }
            }
            return List.copyOf(picked.values());
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

    /** A warning shown in Settings when a saved model is no longer listed. */
    public String selectionWarning() {
        synchronized (lock) {
            return selectionWarning;
        }
    }

    /** When the catalog currently loaded in memory was fetched. */
    public Optional<Instant> catalogFetchedAt() {
        synchronized (lock) {
            return catalogFetchedAt;
        }
    }

    /** Number of OpenRouter models in the loaded catalog, excluding local. */
    public int catalogModelCount() {
        synchronized (lock) {
            return catalogModels.size();
        }
    }

    /** True when the catalog is missing or older than {@link #CATALOG_TTL}. */
    public boolean isCatalogStale() {
        synchronized (lock) {
            if (catalogModels.isEmpty() || catalogFetchedAt.isEmpty()) {
                return true;
            }
            return catalogFetchedAt.get().plus(CATALOG_TTL).isBefore(Instant.now());
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
            try {
                store.rememberRecentModelId(model.id());
                recentModelIds = List.copyOf(store.recentModelIds());
            } catch (IOException | RuntimeException ignored) {
                // The selection is already saved; recents only affect the short picker.
            }
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

    /**
     * Refreshes the OpenRouter catalog on the background executor. Concurrent
     * calls share one request. The returned future completes with a warning
     * instead of throwing, and never clears the previous list on failure.
     */
    public CompletableFuture<CatalogRefreshResult> refreshModelCatalog() {
        synchronized (lock) {
            if (catalogRefresh != null) {
                return catalogRefresh;
            }
            CompletableFuture<CatalogRefreshResult> future;
            try {
                future = CompletableFuture.supplyAsync(this::fetchCatalog, catalogExecutor);
            } catch (RejectedExecutionException e) {
                return CompletableFuture.completedFuture(new CatalogRefreshResult(
                        false,
                        catalogModels.size(),
                        catalogFetchedAt.orElse(null),
                        "Puppy is shutting down; the model list was not refreshed."));
            }
            catalogRefresh = future;
            future.whenComplete((result, error) -> {
                synchronized (lock) {
                    if (catalogRefresh == future) {
                        catalogRefresh = null;
                    }
                }
            });
            return future;
        }
    }

    /** Starts a background refresh only when the 24-hour cache is stale. */
    public CompletableFuture<CatalogRefreshResult> refreshModelCatalogIfStale() {
        if (!isCatalogStale()) {
            synchronized (lock) {
                return CompletableFuture.completedFuture(new CatalogRefreshResult(
                        true,
                        catalogModels.size(),
                        catalogFetchedAt.orElse(null),
                        null));
            }
        }
        return refreshModelCatalog();
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

    private CatalogRefreshResult fetchCatalog() {
        try {
            OpenRouterModelCatalog.Catalog fetched =
                    new OpenRouterModelCatalog(config.openRouterBaseUrl()).fetch();
            if (fetched.models().isEmpty()) {
                return new CatalogRefreshResult(
                        false,
                        catalogModelCount(),
                        catalogFetchedAt().orElse(null),
                        "OpenRouter returned no chat models with at least 16k context. "
                                + "The saved list is unchanged.");
            }

            Instant now = Instant.now();
            String cacheWarning = null;
            try {
                store.saveOpenRouterCatalog(fetched.rawJson(), now);
            } catch (IOException | RuntimeException e) {
                cacheWarning = "The model list was refreshed, but Puppy couldn't save it "
                        + "for offline use.";
            }

            synchronized (lock) {
                catalogModels = fetched.models();
                catalogFetchedAt = Optional.of(now);
                refreshLocked();
            }
            return new CatalogRefreshResult(
                    true, fetched.models().size(), now, cacheWarning);
        } catch (AiException e) {
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? "Puppy couldn't refresh the model list. The saved list is unchanged."
                    : e.getMessage();
            return new CatalogRefreshResult(
                    false,
                    catalogModels.size(),
                    catalogFetchedAt.orElse(null),
                    message);
        } catch (RuntimeException e) {
            return new CatalogRefreshResult(
                    false,
                    catalogModels.size(),
                    catalogFetchedAt.orElse(null),
                    "Puppy couldn't refresh the model list. The saved list is unchanged.");
        }
    }

    private List<AiModel> loadCachedCatalogLocked() {
        return store.openRouterCatalog()
                .map(raw -> {
                    try {
                        return OpenRouterModelCatalog.parseModels(raw);
                    } catch (IOException | RuntimeException e) {
                        return List.<AiModel>of();
                    }
                })
                .filter(models -> !models.isEmpty())
                .orElse(List.of());
    }

    private void refreshLocked() {
        String environmentKey = config.hasOpenRouterApiKey() ? config.openRouterApiKey() : null;
        openRouterKeyFromEnvironment = environmentKey != null;
        openRouterKey = environmentKey != null
                ? environmentKey
                : store.storedOpenRouterApiKey().orElse(null);

        if (catalogModels.isEmpty()) {
            availableModels = AiModels.catalog(config.ollamaModel());
        } else {
            List<AiModel> merged = new ArrayList<>(catalogModels);
            merged.add(AiModels.localModel(config.ollamaModel()));
            availableModels = List.copyOf(merged);
        }

        String savedId = store.selectedModelId().orElse(null);
        AiModel chosen = resolveSelectionLocked(savedId);
        selectedModel = chosen;
        provider = createProviderLocked(chosen);
    }

    /**
     * Keeps an explicitly saved model even after OpenRouter stops listing it.
     * The recovered entry is appended to the list so the combo box can show it,
     * and Settings displays a warning instead of silently switching models.
     */
    private AiModel resolveSelectionLocked(String savedId) {
        String wantedId = savedId != null
                ? savedId
                : selectedModel == null ? null : selectedModel.id();
        AiModel found = findModelLocked(wantedId);
        if (found != null) {
            selectionWarning = null;
            return found;
        }

        if (savedId != null) {
            AiModel recovered = recoverSavedModel(savedId);
            if (recovered != null) {
                List<AiModel> withRecovered = new ArrayList<>(availableModels);
                withRecovered.add(recovered);
                availableModels = List.copyOf(withRecovered);
                selectionWarning = "The saved model \"" + recovered.displayName()
                        + "\" is no longer in OpenRouter's list. Puppy kept it selected. "
                        + "Refresh the list or choose another model if replies fail.";
                return recovered;
            }
        }

        selectionWarning = null;
        return defaultModelLocked();
    }

    /** Rebuilds an OpenRouter model entry from an id kept in local settings. */
    private AiModel recoverSavedModel(String savedId) {
        String prefix = AiModels.OPENROUTER_PROVIDER + ":";
        if (!savedId.startsWith(prefix)) {
            return null;
        }
        String modelName = savedId.substring(prefix.length()).strip();
        if (modelName.isEmpty()) {
            return null;
        }
        return new AiModel(
                savedId,
                AiModels.OPENROUTER_PROVIDER,
                "OpenRouter",
                modelName,
                AiModels.friendlyModelName(modelName),
                true,
                AiModelDetails.unknown());
    }

    private AiModel findModelLocked(String id) {
        if (id == null) {
            return null;
        }
        for (AiModel model : availableModels) {
            if (model.id().equals(id)) {
                return model;
            }
        }
        return null;
    }

    private static void addModel(Map<String, AiModel> models, AiModel model) {
        if (model != null) {
            models.putIfAbsent(model.id(), model);
        }
    }

    /** Cloud-first default when a key exists, local otherwise. */
    private AiModel defaultModelLocked() {
        if (openRouterKey != null) {
            AiModel preferred = findModelLocked(AiModels.OPENROUTER_GEMINI_FLASH);
            if (preferred != null && preferred.cloud()) {
                return preferred;
            }
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
                    openRouterKey,
                    model.modelName(),
                    config.openRouterBaseUrl(),
                    model.supportsJson());
        }
        return new OllamaClient(config.ollamaUrl(), model.modelName());
    }

    /**
     * Outcome of a catalog refresh. {@code success=false} carries a safe warning
     * for Settings; the in-memory list has already been left unchanged.
     */
    public record CatalogRefreshResult(
            boolean success,
            int modelCount,
            Instant fetchedAt,
            String message
    ) {
    }
}
