package com.puppy.ai;

import java.util.List;
import java.util.Objects;

/** The models Puppy can offer when the matching provider is configured. */
public final class AiModels {

    public static final String OLLAMA_PROVIDER = "ollama";
    public static final String OPENROUTER_PROVIDER = "openrouter";

    public static final String OPENROUTER_GEMINI_FLASH =
            OPENROUTER_PROVIDER + ":google/gemini-2.5-flash";
    public static final String OPENROUTER_GEMINI_FLASH_LITE =
            OPENROUTER_PROVIDER + ":google/gemini-2.5-flash-lite";
    public static final String OPENROUTER_GEMINI_PRO =
            OPENROUTER_PROVIDER + ":google/gemini-2.5-pro";

    private AiModels() {
    }

    /**
     * Returns the full catalog, with the configured local model last. Cloud
     * models come first because they are the default when their key exists.
     */
    public static List<AiModel> catalog(String localModelName) {
        Objects.requireNonNull(localModelName, "localModelName");
        return List.of(
                new AiModel(OPENROUTER_GEMINI_FLASH, OPENROUTER_PROVIDER, "OpenRouter",
                        "google/gemini-2.5-flash", "Gemini 2.5 Flash", true),
                new AiModel(OPENROUTER_GEMINI_FLASH_LITE, OPENROUTER_PROVIDER, "OpenRouter",
                        "google/gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite", true),
                new AiModel(OPENROUTER_GEMINI_PRO, OPENROUTER_PROVIDER, "OpenRouter",
                        "google/gemini-2.5-pro", "Gemini 2.5 Pro", true),
                new AiModel(localModelId(localModelName), OLLAMA_PROVIDER, "Ollama",
                        localModelName, friendlyModelName(localModelName), false));
    }

    public static String localModelId(String modelName) {
        return OLLAMA_PROVIDER + ":" + modelName;
    }

    /**
     * Turns a registry-style model name into something short enough for the
     * UI, e.g. {@code hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M} becomes
     * {@code Qwen3.5 9B}.
     */
    public static String friendlyModelName(String modelName) {
        String name = modelName == null ? "" : modelName.strip();
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
        return name.isBlank() ? "Local model" : name;
    }
}
