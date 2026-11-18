package com.puppy.ai;

import java.util.Locale;

/**
 * Extra facts about a model that help the Settings picker without changing how
 * a request is sent.
 *
 * <p>{@code contextLength} is the model's maximum context window in tokens when
 * the provider publishes one, otherwise {@code 0}. {@code pricingLabel} is a
 * short human-readable price such as {@code $0.30/M in · $2.50/M out} or
 * {@code Free}. {@code supportsJson} records whether the provider advertises
 * OpenAI-style JSON mode; chat still works when it does not, because Puppy asks
 * for JSON in the prompt as a fallback.</p>
 *
 * @param contextLength maximum input context in tokens, or 0 when unknown
 * @param pricingLabel  short text for the model browser, never blank
 * @param supportsJson  true when the provider supports response_format JSON
 * @param free          true when the model is offered at no cost
 */
public record AiModelDetails(
        int contextLength,
        String pricingLabel,
        boolean supportsJson,
        boolean free
) {

    private static final String UNKNOWN_PRICE = "Price unavailable";
    private static final String LOCAL_PRICE = "Local";

    public AiModelDetails {
        if (contextLength < 0) {
            throw new IllegalArgumentException("contextLength must not be negative");
        }
        pricingLabel = pricingLabel == null || pricingLabel.isBlank()
                ? UNKNOWN_PRICE
                : pricingLabel.strip();
    }

    /** Metadata for a model whose details are not known yet. */
    public static AiModelDetails unknown() {
        return new AiModelDetails(0, UNKNOWN_PRICE, true, false);
    }

    /** Metadata for the configured Ollama model. */
    public static AiModelDetails local() {
        return new AiModelDetails(0, LOCAL_PRICE, true, false);
    }

    public boolean hasContextLength() {
        return contextLength > 0;
    }

    /** Compact context size such as {@code 16k}, {@code 128k} or {@code 1M}. */
    public String contextLabel() {
        if (contextLength <= 0) {
            return "Unknown context";
        }
        if (contextLength >= 1_000_000) {
            return trim(contextLength / 1_000_000.0) + "M";
        }
        if (contextLength >= 1_024 && contextLength % 1_024 == 0) {
            return (contextLength / 1_024) + "k";
        }
        if (contextLength >= 1_000) {
            return Math.round(contextLength / 1_000.0) + "k";
        }
        return Integer.toString(contextLength);
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
