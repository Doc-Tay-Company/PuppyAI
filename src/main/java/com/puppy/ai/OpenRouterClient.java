package com.puppy.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.puppy.enums.MessageRole;
import com.puppy.model.ChatMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * HTTP client for the OpenRouter chat completions API.
 *
 * <p>Chat replies use server-sent events from
 * {@code POST /chat/completions} with {@code stream: true}; each content
 * fragment is forwarded so the chat bubble can render it while it arrives.
 * JSON-mode extraction uses the same endpoint without streaming and asks for
 * {@code response_format: {"type":"json_object"}}.</p>
 *
 * <p>The API key travels in the {@code Authorization: Bearer} header, never in
 * the URL or the request body. Error messages are redacted before they reach the
 * UI.</p>
 */
public final class OpenRouterClient implements ChatProvider {

    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final int CHAT_OUTPUT_TOKENS = 4_096;
    private static final int JSON_OUTPUT_TOKENS = 8_192;
    private static final double CHAT_TEMPERATURE = 0.6;
    private static final double JSON_TEMPERATURE = 0.1;

    private final URI baseUri;
    private final URI completionsUri;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenRouterClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL);
    }

    public OpenRouterClient(String apiKey, String model, String baseUrl) {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(model, "model");
        String cleanedKey = apiKey.strip();
        if (cleanedKey.isEmpty()) {
            throw new IllegalArgumentException("An OpenRouter API key is required");
        }
        this.apiKey = cleanedKey;
        this.model = model.strip();
        if (this.model.isEmpty()) {
            throw new IllegalArgumentException("An OpenRouter model is required");
        }

        String cleanedUrl = Objects.requireNonNull(baseUrl, "baseUrl").strip();
        while (cleanedUrl.endsWith("/")) {
            cleanedUrl = cleanedUrl.substring(0, cleanedUrl.length() - 1);
        }
        this.baseUri = URI.create(cleanedUrl);
        String scheme = baseUri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                    "OpenRouter URL must start with http:// or https://");
        }
        this.completionsUri = URI.create(cleanedUrl + "/chat/completions");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String providerId() {
        return AiModels.OPENROUTER_PROVIDER;
    }

    @Override
    public String providerName() {
        return "OpenRouter";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isCloud() {
        return true;
    }

    @Override
    public String serverLabel() {
        String host = baseUri.getHost();
        return host == null || host.isBlank() ? baseUri.toString() : host;
    }

    @Override
    public boolean isAvailable() {
        // There is no free readiness endpoint; a configured key is the signal
        // Settings uses. Real failures still surface as friendly chat errors.
        return !apiKey.isBlank();
    }

    @Override
    public boolean hasModel() {
        return !apiKey.isBlank();
    }

    @Override
    public String chat(List<ChatMessage> messages, Consumer<String> onToken) throws AiException {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(onToken, "onToken");
        HttpRequest request = request(messages, true);
        HttpResponse<InputStream> response = send(request);
        if (response.statusCode() != 200) {
            throw errorFor(response.statusCode(), readErrorBody(response.body()));
        }
        return readStream(response.body(), onToken);
    }

    @Override
    public String jsonChat(List<ChatMessage> messages) throws AiException {
        Objects.requireNonNull(messages, "messages");
        HttpRequest request = request(messages, false);
        HttpResponse<String> response = sendString(request);
        if (response.statusCode() != 200) {
            throw errorFor(response.statusCode(), response.body());
        }
        return readSingle(response.body());
    }

    private HttpRequest request(List<ChatMessage> messages, boolean streaming) throws AiException {
        byte[] body = requestBody(messages, streaming);
        return HttpRequest.newBuilder(completionsUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "text/event-stream" : "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("X-Title", "Puppy AI")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private byte[] requestBody(List<ChatMessage> messages, boolean streaming) throws AiException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        ArrayNode contents = root.putArray("messages");

        for (ChatMessage message : messages) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            ObjectNode content = contents.addObject();
            content.put("role", switch (message.role()) {
                case SYSTEM -> "system";
                case ASSISTANT -> "assistant";
                case USER -> "user";
            });
            content.put("content", message.content());
        }

        if (contents.isEmpty()) {
            throw new AiException(
                    AiException.Reason.PROTOCOL,
                    "Puppy has no message to send to the model.");
        }

        root.put("stream", streaming);
        root.put("temperature", streaming ? CHAT_TEMPERATURE : JSON_TEMPERATURE);
        root.put("max_tokens", streaming ? CHAT_OUTPUT_TOKENS : JSON_OUTPUT_TOKENS);
        if (!streaming) {
            root.putObject("response_format").put("type", "json_object");
        }

        try {
            return mapper.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new AiException(
                    AiException.Reason.PROTOCOL, "Puppy couldn't prepare the request.", e);
        }
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws AiException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw unavailable(e);
        } catch (HttpTimeoutException e) {
            throw timedOut(e);
        } catch (ConnectException e) {
            throw unavailable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.Reason.INTERRUPTED, "Reply stopped.", e);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AiException(AiException.Reason.INTERRUPTED, "Reply stopped.", e);
            }
            throw unavailable(e);
        }
    }

    private HttpResponse<String> sendString(HttpRequest request) throws AiException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            throw unavailable(e);
        } catch (HttpTimeoutException e) {
            throw timedOut(e);
        } catch (ConnectException e) {
            throw unavailable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.Reason.INTERRUPTED, "Reply stopped.", e);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AiException(AiException.Reason.INTERRUPTED, "Reply stopped.", e);
            }
            throw unavailable(e);
        }
    }

    private String readStream(InputStream body, Consumer<String> onToken) throws AiException {
        StringBuilder reply = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        boolean sawData = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new AiException(AiException.Reason.INTERRUPTED, "Reply stopped.");
                }
                // OpenRouter sends ": OPENROUTER PROCESSING" comment lines while
                // an upstream model is warming up.
                if (line.startsWith(":")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    if (!sawData && !line.isBlank()) {
                        raw.append(line).append('\n');
                    }
                    continue;
                }
                String data = line.substring("data:".length()).strip();
                if (data.isEmpty()) {
                    continue;
                }
                sawData = true;
                if ("[DONE]".equals(data)) {
                    break;
                }
                readChunk(parse(data), reply, onToken);
            }
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AiException(AiException.Reason.INTERRUPTED, "Reply stopped.", e);
            }
            throw new AiException(
                    AiException.Reason.UNAVAILABLE,
                    "The connection to OpenRouter was interrupted before the reply finished.",
                    e);
        }

        // A proxy or a stubbed server may ignore stream=true and return one JSON
        // document. Parse it once the stream ends so replies still work.
        if (!sawData && !raw.isEmpty()) {
            readWhole(raw.toString(), reply, onToken);
        }
        if (reply.isEmpty()) {
            throw new AiException(
                    AiException.Reason.PROTOCOL,
                    "OpenRouter returned an empty reply. Try rephrasing your question.");
        }
        return reply.toString();
    }

    private String readSingle(String body) throws AiException {
        StringBuilder reply = new StringBuilder();
        readWhole(body, reply, token -> { });
        if (reply.isEmpty()) {
            throw new AiException(
                    AiException.Reason.PROTOCOL,
                    "OpenRouter returned an empty reply. Try rephrasing your question.");
        }
        return reply.toString();
    }

    private JsonNode parse(String json) throws AiException {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AiException(
                    AiException.Reason.PROTOCOL,
                    "OpenRouter sent a response Puppy couldn't read.", e);
        }
    }

    private void readChunk(JsonNode node, StringBuilder reply, Consumer<String> onToken)
            throws AiException {
        checkError(node);
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode choice = choices.get(0);
        String token = textOf(choice.path("delta").get("content"));
        if (token.isEmpty()) {
            token = textOf(choice.path("message").get("content"));
        }
        if (!token.isEmpty()) {
            reply.append(token);
            onToken.accept(token);
        }
        String finishReason = choice.path("finish_reason").asText("");
        if (reply.isEmpty() && isBlockingFinish(finishReason)) {
            throw new AiException(
                    AiException.Reason.PROTOCOL,
                    "OpenRouter stopped that reply for safety reasons. Try rephrasing it.");
        }
        if (reply.isEmpty() && "length".equals(finishReason)) {
            throw new AiException(
                    AiException.Reason.PROTOCOL,
                    "OpenRouter reached its output limit before answering. "
                            + "Try a shorter request.");
        }
    }

    private void readWhole(String body, StringBuilder reply, Consumer<String> onToken)
            throws AiException {
        JsonNode node = parse(body);
        checkError(node);
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode choice = choices.get(0);
        String text = textOf(choice.path("message").get("content"));
        if (text.isEmpty()) {
            text = textOf(choice.path("text"));
        }
        if (!text.isEmpty()) {
            reply.append(text);
            onToken.accept(text);
        }
    }

    private void checkError(JsonNode node) throws AiException {
        JsonNode error = node.get("error");
        if (error == null || error.isNull()) {
            return;
        }
        JsonNode code = error.get("code");
        int status = code != null && code.isNumber() ? code.asInt() : 0;
        throw errorFor(status, error.toString());
    }

    /** Reads a text value from a string or an array of content parts. */
    private static String textOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : node) {
                if (part.isTextual()) {
                    text.append(part.asText(""));
                } else {
                    String value = part.path("text").asText("");
                    if (!value.isEmpty()) {
                        text.append(value);
                    }
                }
            }
            return text.toString();
        }
        return "";
    }

    private static boolean isBlockingFinish(String finishReason) {
        return switch (finishReason) {
            case "content_filter", "safety", "blocklist", "recitation", "prohibited_content" -> true;
            default -> false;
        };
    }

    private AiException unavailable(Throwable cause) {
        return new AiException(
                AiException.Reason.UNAVAILABLE,
                "I can't reach OpenRouter. Check your internet connection, then try again.",
                cause);
    }

    private AiException timedOut(Throwable cause) {
        return new AiException(
                AiException.Reason.TIMEOUT,
                "OpenRouter took too long to answer. Try again, or switch to the local model in Settings.",
                cause);
    }

    private AiException errorFor(int status, String detail) {
        String readable = readableError(detail);
        String normalized = readable.toLowerCase(Locale.ROOT);
        if (status == 401 || status == 403
                || normalized.contains("invalid api key")
                || normalized.contains("no auth credentials")
                || normalized.contains("unauthorized")
                || normalized.contains("user not found")
                || normalized.contains("permission denied")) {
            return new AiException(
                    AiException.Reason.AUTHENTICATION,
                    "OpenRouter rejected the API key. Open More > Settings and save a valid key.");
        }
        if (status == 402
                || normalized.contains("insufficient credits")
                || normalized.contains("payment required")
                || normalized.contains("not enough credits")) {
            return new AiException(
                    AiException.Reason.HTTP,
                    "OpenRouter says this account doesn't have enough credits for that request. "
                            + "Add credits, or choose the local model in Settings.");
        }
        if (status == 429 || normalized.contains("rate limit")
                || normalized.contains("too many requests")
                || normalized.contains("resource_exhausted")) {
            return new AiException(
                    AiException.Reason.RATE_LIMIT,
                    "OpenRouter is rate-limiting Puppy right now. Try again in a moment, or switch "
                            + "to the local model in Settings.");
        }
        if (status == 404 || normalized.contains("model not found")
                || normalized.contains("no endpoints found")
                || normalized.contains("not a valid model")) {
            return new AiException(
                    AiException.Reason.MODEL_NOT_FOUND,
                    "The OpenRouter model \"" + model + "\" isn't available for this key.");
        }
        String cleaned = readable.isBlank()
                ? "HTTP " + status
                : readable.replaceAll("\\s+", " ").strip();
        if (cleaned.length() > 180) {
            cleaned = cleaned.substring(0, 177) + "...";
        }
        return new AiException(
                AiException.Reason.HTTP, "OpenRouter returned an error: " + cleaned);
    }

    /**
     * OpenRouter returns errors as {@code {"error":{"message":"..."}}}. Pull out
     * the readable message and remove any accidental key echo.
     */
    private String readableError(String detail) {
        if (detail == null) {
            return "";
        }
        String trimmed = redact(detail).strip();
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }
        try {
            JsonNode root = mapper.readTree(trimmed);
            JsonNode error = root.get("error");
            if (error == null || error.isNull()) {
                return trimmed;
            }
            if (error.isTextual()) {
                return redact(error.asText()).strip();
            }
            JsonNode message = error.get("message");
            if (message != null && message.isTextual()) {
                return redact(message.asText()).strip();
            }
            return trimmed;
        } catch (JsonProcessingException e) {
            return trimmed;
        }
    }

    private String redact(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(apiKey, "[redacted]");
    }

    private static String readErrorBody(InputStream body) {
        try (InputStream input = body) {
            byte[] bytes = input.readNBytes(4_096);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
