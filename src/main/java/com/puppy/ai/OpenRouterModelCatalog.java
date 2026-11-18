package com.puppy.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reads OpenRouter's public model list from {@code GET {baseUrl}/models}.
 *
 * <p>The endpoint needs no API key, so browsing models never exposes the user's
 * credentials. OpenRouter returns hundreds of entries, including image, audio
 * and moderation models that cannot replace Puppy's chat model. Only entries
 * that output text and accept at least a 16k context are kept. Models without
 * {@code response_format} support stay in the list; the chat client uses a
 * prompt-only JSON fallback for them.</p>
 */
public final class OpenRouterModelCatalog {

    /** The Focus prompt already uses about 16k, so smaller models fail. */
    public static final int MIN_CONTEXT_LENGTH = 16_384;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final URI modelsUri;
    private final HttpClient httpClient;

    public OpenRouterModelCatalog(String baseUrl) {
        String cleanedUrl = Objects.requireNonNull(baseUrl, "baseUrl").strip();
        while (cleanedUrl.endsWith("/")) {
            cleanedUrl = cleanedUrl.substring(0, cleanedUrl.length() - 1);
        }
        URI baseUri = URI.create(cleanedUrl);
        String scheme = baseUri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                    "OpenRouter URL must start with http:// or https://");
        }
        this.modelsUri = URI.create(cleanedUrl + "/models");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Fetches and filters the public catalog. No API key is sent. */
    public Catalog fetch() throws AiException {
        HttpRequest request = HttpRequest.newBuilder(modelsUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("X-Title", "Puppy AI")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            throw unavailable(e);
        } catch (HttpTimeoutException e) {
            throw new AiException(
                    AiException.Reason.TIMEOUT,
                    "OpenRouter took too long to send its model list. Try again in a moment.",
                    e);
        } catch (ConnectException e) {
            throw unavailable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(
                    AiException.Reason.INTERRUPTED, "Model refresh stopped.", e);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AiException(
                        AiException.Reason.INTERRUPTED, "Model refresh stopped.", e);
            }
            throw unavailable(e);
        }

        if (response.statusCode() != 200) {
            throw new AiException(
                    AiException.Reason.HTTP,
                    "OpenRouter returned HTTP " + response.statusCode()
                            + " for its model list. Keeping the saved list.");
        }
        try {
            return new Catalog(response.body(), parseModels(response.body()));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new AiException(
                    AiException.Reason.PROTOCOL,
                    "OpenRouter sent a model list Puppy couldn't read. Keeping the saved list.",
                    e);
        }
    }

    /** Convenience alias for callers that only need the filtered models. */
    public List<AiModel> fetchModels() throws AiException {
        return fetch().models();
    }

    /**
     * Parses and filters a cached or freshly fetched model list.
     *
     * @throws JsonProcessingException when the body is not JSON
     * @throws IllegalArgumentException when the JSON has no {@code data} array
     */
    public static List<AiModel> parseModels(String rawJson) throws JsonProcessingException {
        Objects.requireNonNull(rawJson, "rawJson");
        JsonNode root = MAPPER.readTree(rawJson);
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalArgumentException("OpenRouter model list has no data array");
        }

        Map<String, AiModel> models = new LinkedHashMap<>();
        for (JsonNode node : data) {
            if (node == null || !node.isObject()) {
                continue;
            }
            String modelId = text(node, "id");
            if (modelId.isBlank()) {
                continue;
            }
            int contextLength = node.path("context_length").asInt(0);
            if (contextLength < MIN_CONTEXT_LENGTH) {
                continue;
            }
            JsonNode architecture = node.path("architecture");
            if (!isChatModel(modelId, architecture)) {
                continue;
            }

            boolean supportsJson = includesText(
                    node.path("supported_parameters"), "response_format");
            Pricing pricing = readPricing(node.path("pricing"), modelId);
            String displayName = text(node, "name");
            if (displayName.isBlank()) {
                displayName = AiModels.friendlyModelName(modelId);
            }

            String id = AiModels.OPENROUTER_PROVIDER + ":" + modelId;
            models.putIfAbsent(id, new AiModel(
                    id,
                    AiModels.OPENROUTER_PROVIDER,
                    "OpenRouter",
                    modelId,
                    displayName,
                    true,
                    new AiModelDetails(
                            contextLength,
                            pricing.label(),
                            supportsJson,
                            pricing.free())));
        }

        List<AiModel> result = new ArrayList<>(models.values());
        result.sort(Comparator.comparing(AiModel::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AiModel::modelName));
        return List.copyOf(result);
    }

    /**
     * Keeps text-output chat models and drops image/audio/moderation-only ones.
     * Some old entries only carry {@code architecture.modality}; accept those
     * when they end in {@code ->text}.
     */
    private static boolean isChatModel(String modelId, JsonNode architecture) {
        String loweredId = modelId.toLowerCase(Locale.ROOT);
        if (loweredId.contains("moderation")) {
            return false;
        }
        JsonNode outputs = architecture.path("output_modalities");
        if (outputs.isArray() && !outputs.isEmpty()) {
            return includesText(outputs, "text");
        }
        String modality = architecture.path("modality").asText("").toLowerCase(Locale.ROOT);
        return modality.endsWith("->text") || modality.equals("text");
    }

    private static boolean includesText(JsonNode values, String wanted) {
        if (values == null || !values.isArray()) {
            return false;
        }
        for (JsonNode value : values) {
            if (value.isTextual() && wanted.equalsIgnoreCase(value.asText().strip())) {
                return true;
            }
        }
        return false;
    }

    private static Pricing readPricing(JsonNode pricing, String modelId) {
        BigDecimal prompt = decimal(pricing, "prompt");
        BigDecimal completion = decimal(pricing, "completion");
        boolean hasPrompt = prompt != null;
        boolean hasCompletion = completion != null;

        boolean free = modelId.endsWith(":free")
                || (hasPrompt && hasCompletion
                && prompt.signum() == 0 && completion.signum() == 0);
        if (free) {
            return new Pricing("Free", true);
        }

        List<String> parts = new ArrayList<>(2);
        if (hasPrompt) {
            parts.add("$" + money(prompt) + "/M in");
        }
        if (hasCompletion) {
            parts.add("$" + money(completion) + "/M out");
        }
        return new Pricing(parts.isEmpty() ? "Price unavailable" : String.join(" · ", parts), false);
    }

    private static BigDecimal decimal(JsonNode pricing, String field) {
        if (pricing == null) {
            return null;
        }
        JsonNode value = pricing.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            BigDecimal number = value.isNumber()
                    ? value.decimalValue()
                    : new BigDecimal(value.asText().strip());
            return number.signum() < 0 ? null : number;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Converts a per-token price into a compact per-million-token label. */
    private static String money(BigDecimal perToken) {
        BigDecimal perMillion = perToken.multiply(ONE_MILLION);
        BigDecimal rounded = perMillion.compareTo(BigDecimal.ONE) < 0
                ? perMillion.setScale(4, RoundingMode.HALF_UP)
                : perMillion.setScale(2, RoundingMode.HALF_UP);
        return rounded.stripTrailingZeros().toPlainString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").strip();
    }

    private static AiException unavailable(Throwable cause) {
        return new AiException(
                AiException.Reason.UNAVAILABLE,
                "I can't reach OpenRouter to refresh the model list. "
                        + "Check your internet connection; the saved list is unchanged.",
                cause);
    }

    /** The raw body and its filtered models. The raw body is cached for offline use. */
    public record Catalog(String rawJson, List<AiModel> models) {
        public Catalog {
            Objects.requireNonNull(rawJson, "rawJson");
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    private record Pricing(String label, boolean free) {
    }
}
