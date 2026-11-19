package com.puppy.service;

import com.puppy.ai.AiException;
import com.puppy.ai.AiModels;
import com.puppy.config.AiSettingsStore;
import com.puppy.config.AppConfig;
import com.puppy.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void acceptsCustomOpenRouterModelIdsAndKeepsThemWhenSwitchingLocal() throws Exception {
        AiSettingsStore store = new AiSettingsStore(tempDirectory);
        ModelService service = service(null, store);
        String customModelId = "vendor/custom-model:free";

        service.selectOpenRouterModel(customModelId);

        assertTrue(service.isCloud());
        assertEquals(customModelId, service.modelName());
        assertEquals(customModelId, service.openRouterModelName());

        service.selectLocalModel();
        ModelService restarted = service(null, new AiSettingsStore(tempDirectory));

        assertFalse(restarted.isCloud());
        assertEquals(customModelId, restarted.openRouterModelName());
        restarted.selectOpenRouterModel(restarted.openRouterModelName());
        assertEquals(customModelId, restarted.modelName());
    }

    @Test
    void rejectsCustomOpenRouterModelIdsContainingSpaces() {
        ModelService service = service(null, new AiSettingsStore(tempDirectory));

        assertThrows(IllegalArgumentException.class,
                () -> service.selectOpenRouterModel("vendor/model name"));
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

    private ModelService service(String environmentKey, AiSettingsStore store) {
        AppConfig config = new AppConfig(
                "Puppy AI",
                "test",
                true,
                tempDirectory,
                "http://localhost:11434",
                LOCAL_MODEL,
                environmentKey,
                AppConfig.DEFAULT_OPENROUTER_BASE_URL);
        return new ModelService(config, store);
    }
}
