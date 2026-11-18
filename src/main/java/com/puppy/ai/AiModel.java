package com.puppy.ai;

import java.util.Objects;

/**
 * One model entry shown in Settings.
 *
 * @param id           stable id stored in the user's local settings
 * @param providerId   provider family such as {@code openrouter} or {@code ollama}
 * @param providerName human-readable provider name
 * @param modelName    exact id sent to the provider API
 * @param displayName  short name shown in the UI
 * @param cloud        true when the request leaves this computer
 * @param details      catalog facts such as context size, price and JSON support
 */
public record AiModel(
        String id,
        String providerId,
        String providerName,
        String modelName,
        String displayName,
        boolean cloud,
        AiModelDetails details
) {

    public AiModel {
        requireText(id, "id");
        requireText(providerId, "providerId");
        requireText(providerName, "providerName");
        requireText(modelName, "modelName");
        requireText(displayName, "displayName");
        details = details == null ? AiModelDetails.unknown() : details;
    }

    /** Keeps the original six-argument call sites readable. */
    public AiModel(String id, String providerId, String providerName, String modelName,
                   String displayName, boolean cloud) {
        this(id, providerId, providerName, modelName, displayName, cloud,
                AiModelDetails.unknown());
    }

    public int contextLength() {
        return details.contextLength();
    }

    public String pricingLabel() {
        return details.pricingLabel();
    }

    public boolean supportsJson() {
        return details.supportsJson();
    }

    public boolean free() {
        return details.free();
    }

    public String locationLabel() {
        return cloud ? "cloud" : "local";
    }

    public String describe() {
        return displayName + " · " + modelName + " · " + providerName + " · " + locationLabel();
    }

    /** Label used by the Settings model picker. */
    @Override
    public String toString() {
        return cloud ? providerName + " · " + displayName : displayName + " · " + locationLabel();
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
