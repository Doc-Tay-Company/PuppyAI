package com.puppy.service;

import com.puppy.ai.AiException;
import com.puppy.ai.AiModels;
import com.puppy.config.AiSettingsStore;
import com.puppy.config.AppConfig;
import com.puppy.model.ChatMessage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelServiceTest {

    private static final String LOCAL_MODEL = "hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M";
    private static final String OPENROUTER_KEY = "sk-or-v1-model-service-key-1234567890";

    @TempDir
    Path tempDirectory;

    @Test
    void prefersCloudWhenAKeyIsAvailable() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);
        store.saveOpenRouterApiKey(OPENROUTER_KEY);

        ModelService service = service(null, store);

        assertTrue(service.isCloud());
        assertEquals(AiModels.OPENROUTER_GEMINI_FLASH, service.selectedModel().id());
        assertEquals("Gemini 2.5 Flash · cloud", service.statusLabel());
        assertEquals(4, service.availableModels().size());
        assertTrue(service.describeSelectedModel().contains("google/gemini-2.5-flash"));
    }

    @Test
    void fallsBackToLocalWithoutAKeyButStillListsCloudModels() {
        ModelService service = service(null, new AiSettingsStore(tempDirectory));

        assertFalse(service.isCloud());
        assertEquals(AiModels.OLLAMA_PROVIDER, service.selectedModel().providerId());
        assertEquals("Qwen3.5 9B · local", service.statusLabel());
        assertEquals(4, service.availableModels().size(),
                "Cloud models stay visible so Settings can collect their key");
        assertTrue(service.availableModels().stream().anyMatch(model -> model.cloud()));
        assertFalse(service.hasOpenRouterKey());
    }

    @Test
    void selectingCloudWithoutAKeyKeepsTheChoiceAndExplainsTheMissingKey() throws Exception {
        ModelService service = service(null, new AiSettingsStore(tempDirectory));

        service.selectModel(AiModels.OPENROUTER_GEMINI_FLASH);

        assertTrue(service.isCloud());
        assertEquals(AiModels.OPENROUTER_GEMINI_FLASH, service.selectedModel().id());
        assertFalse(service.hasOpenRouterKey());

        AiException error = assertThrows(AiException.class,
                () -> service.streamChat(List.of(ChatMessage.user("hi")), token -> { }));
        assertEquals(AiException.Reason.AUTHENTICATION, error.reason());
        assertTrue(error.getMessage().contains("Settings"));

        ModelService reloaded = service(null, new AiSettingsStore(tempDirectory));
        assertEquals(AiModels.OPENROUTER_GEMINI_FLASH, reloaded.selectedModel().id());
    }

    @Test
    void savingAKeyKeepsAnExplicitLocalChoice() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);
        ModelService service = service(null, store);
        service.selectModel(AiModels.localModelId(LOCAL_MODEL));

        service.saveOpenRouterKey(OPENROUTER_KEY);

        assertTrue(service.hasOpenRouterKey());
        assertFalse(service.isCloud(),
                "An explicit local choice must not be replaced when a key appears");

        ModelService reloaded = service(null, new AiSettingsStore(tempDirectory));
        assertFalse(reloaded.isCloud());
    }

    @Test
    void switchesCloudModelsAndPersistsTheChoice() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);
        store.saveOpenRouterApiKey(OPENROUTER_KEY);
        ModelService service = service(null, store);

        service.selectModel(AiModels.OPENROUTER_GEMINI_PRO);

        assertEquals(AiModels.OPENROUTER_GEMINI_PRO, service.selectedModel().id());
        assertEquals(AiModels.OPENROUTER_GEMINI_PRO,
                service(null, new AiSettingsStore(tempDirectory)).selectedModel().id());
    }

    @Test
    void clearingTheKeyKeepsTheCloudChoiceAndSavingANewKeyRestoresReplies()
            throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);
        ModelService service = service(null, store);
        service.saveOpenRouterKey(OPENROUTER_KEY);
        service.selectModel(AiModels.OPENROUTER_GEMINI_FLASH);
        assertEquals(AiModels.OPENROUTER_GEMINI_FLASH, service.selectedModel().id());

        service.clearOpenRouterKey();

        assertFalse(service.hasOpenRouterKey());
        assertTrue(service.isCloud(),
                "The selected OpenRouter model should stay selected so its key can be re-entered");
        assertEquals(AiModels.OPENROUTER_GEMINI_FLASH, service.selectedModel().id());
        assertThrows(AiException.class,
                () -> service.streamChat(List.of(ChatMessage.user("hi")), token -> { }));

        service.saveOpenRouterKey(OPENROUTER_KEY);

        assertTrue(service.hasOpenRouterKey());
        assertEquals(AiModels.OPENROUTER_GEMINI_FLASH, service.selectedModel().id());
    }

    @Test
    void clearingTheKeyKeepsTheCloudDefaultSelectedForReEntry() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);
        store.saveOpenRouterApiKey(OPENROUTER_KEY);
        ModelService service = service(null, store);
        assertEquals(AiModels.OPENROUTER_GEMINI_FLASH, service.selectedModel().id());

        service.clearOpenRouterKey();

        assertFalse(service.hasOpenRouterKey());
        assertTrue(service.isCloud(),
                "The startup default should stay selected after its key is removed");
        assertEquals(AiModels.OPENROUTER_GEMINI_FLASH,
                service(null, new AiSettingsStore(tempDirectory)).selectedModel().id());
    }

    @Test
    void environmentKeysWinAndCannotBeEditedFromSettings() {
        ModelService service = service(
                "env-openrouter-key-1234567890", new AiSettingsStore(tempDirectory));

        assertTrue(service.hasOpenRouterKey());
        assertTrue(service.isOpenRouterKeyFromEnvironment());
        assertFalse(service.canEditOpenRouterKey());
        assertTrue(service.isCloud());
        assertThrows(IllegalStateException.class,
                () -> service.saveOpenRouterKey(OPENROUTER_KEY));
        assertThrows(IllegalStateException.class, service::clearOpenRouterKey);
    }

    @Test
    void keepsASavedModelThatIsNoLongerInTheCatalog() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);
        store.saveSelectedModelId("openrouter:acme/removed-model");
        store.saveOpenRouterCatalog(cachedCatalog(), Instant.now());

        ModelService service = service(null, store);

        assertEquals("openrouter:acme/removed-model", service.selectedModel().id());
        assertTrue(service.selectedModel().cloud());
        assertNotNull(service.selectionWarning());
        assertTrue(service.selectionWarning().contains("no longer"));
        assertTrue(service.availableModels().stream()
                .anyMatch(model -> model.id().equals("openrouter:acme/removed-model")));
        assertEquals("openrouter:acme/removed-model",
                service.modelPickerModels().get(0).id(),
                "The kept model must stay visible in the short picker");
    }

    @Test
    void refreshesThePublicCatalogCachesItAndReloadsOffline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/api/v1/models", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, cachedCatalog());
        });
        server.start();
        try {
            AiSettingsStore store = new AiSettingsStore(tempDirectory);
            ModelService service = service(null, store, baseUrl(server));

            ModelService.CatalogRefreshResult result =
                    service.refreshModelCatalog().join();

            assertTrue(result.success(), result.message());
            assertEquals(1, result.modelCount());
            assertEquals(1, requests.get());
            assertEquals(1, service.catalogModelCount());
            assertTrue(service.catalogFetchedAt().isPresent());
            assertTrue(store.openRouterCatalog().isPresent());
            assertTrue(service.availableModels().stream()
                    .anyMatch(model -> model.modelName().equals("acme/cached-chat")));
            assertTrue(service.availableModels().stream().anyMatch(model -> !model.cloud()));

            ModelService reloaded = service(null, new AiSettingsStore(tempDirectory), baseUrl(server));
            assertEquals(1, reloaded.catalogModelCount(),
                    "A cached catalog should load without another HTTP request");
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedRefreshKeepsTheCachedListAndTheSelectedModel() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);
        store.saveSelectedModelId("openrouter:acme/cached-chat");
        store.saveOpenRouterCatalog(cachedCatalog(), Instant.now());
        ModelService service = service(null, store, "http://127.0.0.1:1/api/v1");
        Instant fetchedAt = service.catalogFetchedAt().orElseThrow();

        ModelService.CatalogRefreshResult result =
                service.refreshModelCatalog().join();

        assertFalse(result.success());
        assertTrue(result.message() != null && !result.message().isBlank());
        assertEquals(1, service.catalogModelCount(),
                "A network failure must not clear the cached catalog");
        assertEquals("openrouter:acme/cached-chat", service.selectedModel().id());
        assertEquals(fetchedAt, service.catalogFetchedAt().orElseThrow());
        assertEquals(1, service.availableModels().stream()
                .filter(model -> model.modelName().equals("acme/cached-chat"))
                .count());
    }

    @Test
    void staleCacheStillProvidesModelsUntilTheBackgroundRefreshFinishes() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);
        store.saveOpenRouterCatalog(cachedCatalog(),
                Instant.now().minus(Duration.ofHours(25)));

        ModelService service = service(null, store);

        assertTrue(service.isCatalogStale());
        assertEquals(1, service.catalogModelCount());
        assertTrue(service.availableModels().stream()
                .anyMatch(model -> model.modelName().equals("acme/cached-chat")));
    }

    private ModelService service(String environmentKey, AiSettingsStore store) {
        return service(environmentKey, store, AppConfig.DEFAULT_OPENROUTER_BASE_URL);
    }

    private ModelService service(String environmentKey, AiSettingsStore store, String baseUrl) {
        AppConfig config = new AppConfig(
                "Puppy AI",
                "test",
                true,
                tempDirectory,
                "http://localhost:11434",
                LOCAL_MODEL,
                environmentKey,
                baseUrl);
        return new ModelService(config, store, Runnable::run);
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    private static String cachedCatalog() {
        return """
                {"data":[
                  {"id":"acme/cached-chat","name":"Cached Chat","context_length":32768,
                   "architecture":{"output_modalities":["text"]},
                   "pricing":{"prompt":"0.000001","completion":"0.000002"},
                   "supported_parameters":["response_format"]}
                ]}
                """;
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
