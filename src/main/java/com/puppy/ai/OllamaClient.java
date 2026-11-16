package com.puppy.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * HTTP client for the local Ollama server.
 *
 * <p>Chat replies are streamed as newline-delimited JSON from
 * {@code POST /api/chat}; each content fragment is forwarded to the caller so
 * the UI can render tokens as they arrive. This class contains no UI code and
 * can be used from any background thread.</p>
 */
public final class OllamaClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration AVAILABILITY_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CHAT_TIMEOUT = Duration.ofMinutes(5);
    private static final int NUM_CONTEXT = 16_384;
    private static final int NUM_PREDICT = 2_048;
    private static final double TEMPERATURE = 0.6;

    private final URI baseUri;
    private final URI tagsUri;
    private final URI chatUri;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaClient(String baseUrl, String model) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(model, "model");
        String cleanedUrl = baseUrl.strip();
        while (cleanedUrl.endsWith("/")) {
            cleanedUrl = cleanedUrl.substring(0, cleanedUrl.length() - 1);
        }
        this.baseUri = URI.create(cleanedUrl);
        String scheme = baseUri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Ollama URL must start with http:// or https://");
        }
        this.tagsUri = baseUri.resolve("/api/tags");
        this.chatUri = baseUri.resolve("/api/chat");
        this.model = model.strip();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String model() {
        return model;
    }

    public String baseUrl() {
        return baseUri.toString();
    }

    /** Quick check used to explain connection problems before sending a prompt. */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(tagsUri)
                    .timeout(AVAILABILITY_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** True when the configured model name appears in the server's model list. */
    public boolean hasModel() {
        try {
            HttpRequest request = HttpRequest.newBuilder(tagsUri)
                    .timeout(AVAILABILITY_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            String wanted = normalizeModelName(model);
            JsonNode models = mapper.readTree(response.body()).path("models");
            for (JsonNode node : models) {
                if (wanted.equals(normalizeModelName(node.path("name").asText()))
                        || wanted.equals(normalizeModelName(node.path("model").asText()))) {
                    return true;
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Sends a conversation to Ollama and forwards each content fragment.
     *
     * @return the complete assistant reply
     * @throws OllamaException when the server is unreachable, the model is
     *                         missing, the request fails or it is interrupted
     */
    public String chat(List<ChatMessage> messages, Consumer<String> onToken) throws OllamaException {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(onToken, "onToken");
        if (messages.isEmpty()) {
            throw new OllamaException(
                    OllamaException.Reason.PROTOCOL, "Puppy has no message to send to the model.");
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(chatUri)
                    .timeout(CHAT_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody(messages)))
                    .build();
        } catch (JsonProcessingException e) {
            throw new OllamaException(
                    OllamaException.Reason.PROTOCOL, "Puppy couldn't prepare the request.", e);
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw unavailable(e);
        } catch (HttpTimeoutException e) {
            throw new OllamaException(
                    OllamaException.Reason.TIMEOUT,
                    "Ollama took too long to answer. The model may still be loading; try again in a moment.",
                    e);
        } catch (ConnectException e) {
            throw unavailable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaException(
                    OllamaException.Reason.INTERRUPTED, "Reply stopped.", e);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new OllamaException(
                        OllamaException.Reason.INTERRUPTED, "Reply stopped.", e);
            }
            throw unavailable(e);
        }

        if (response.statusCode() != 200) {
            String detail = readErrorBody(response.body());
            throw errorFor(response.statusCode(), detail);
        }

        StringBuilder reply = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new OllamaException(
                            OllamaException.Reason.INTERRUPTED, "Reply stopped.");
                }
                if (line.isBlank()) {
                    continue;
                }

                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (JsonProcessingException e) {
                    throw new OllamaException(
                            OllamaException.Reason.PROTOCOL,
                            "Ollama sent a response Puppy couldn't read.", e);
                }

                JsonNode error = node.get("error");
                if (error != null && !error.isNull()) {
                    throw errorForResponse(error.asText());
                }

                JsonNode message = node.get("message");
                if (message != null) {
                    String token = message.path("content").asText("");
                    if (!token.isEmpty()) {
                        reply.append(token);
                        onToken.accept(token);
                    }
                }

                if (node.path("done").asBoolean(false)) {
                    break;
                }
            }
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new OllamaException(
                        OllamaException.Reason.INTERRUPTED, "Reply stopped.", e);
            }
            throw new OllamaException(
                    OllamaException.Reason.UNAVAILABLE,
                    "The connection to Ollama was interrupted before the reply finished.",
                    e);
        }

        return reply.toString();
    }

    private byte[] requestBody(List<ChatMessage> messages) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        ArrayNode messageNodes = root.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode node = messageNodes.addObject();
            node.put("role", message.role().wireName());
            node.put("content", message.content());
        }
        root.put("stream", true);
        // Qwen 3.5 supports thinking; the chat UI shows the answer, not the
        // private reasoning, and disabling it makes replies start faster.
        root.put("think", false);
        ObjectNode options = root.putObject("options");
        options.put("temperature", TEMPERATURE);
        options.put("num_ctx", NUM_CONTEXT);
        options.put("num_predict", NUM_PREDICT);
        return mapper.writeValueAsBytes(root);
    }

    private OllamaException unavailable(Throwable cause) {
        return new OllamaException(
                OllamaException.Reason.UNAVAILABLE,
                "I can't reach Ollama at " + baseUri + ". Make sure Ollama is running, then try again.",
                cause);
    }

    private OllamaException errorFor(int status, String detail) {
        String readable = readableError(detail);
        String normalized = readable.toLowerCase(Locale.ROOT);
        if (status == 404 || normalized.contains("not found") || normalized.contains("no such model")) {
            return new OllamaException(
                    OllamaException.Reason.MODEL_NOT_FOUND,
                    "The local model \"" + model + "\" isn't available. Run: ollama pull " + model);
        }
        if (normalized.contains("context length") || normalized.contains("too long")) {
            return new OllamaException(
                    OllamaException.Reason.HTTP,
                    "That request is too long for the local model. Remove a document or start a new conversation.");
        }
        String cleaned = readable.isBlank() ? "HTTP " + status : readable.replaceAll("\\s+", " ").strip();
        if (cleaned.length() > 180) {
            cleaned = cleaned.substring(0, 177) + "...";
        }
        return new OllamaException(
                OllamaException.Reason.HTTP, "Ollama returned an error: " + cleaned);
    }

    private OllamaException errorForResponse(String detail) {
        String readable = readableError(detail);
        String normalized = readable.toLowerCase(Locale.ROOT);
        if (normalized.contains("not found") || normalized.contains("no such model")) {
            return new OllamaException(
                    OllamaException.Reason.MODEL_NOT_FOUND,
                    "The local model \"" + model + "\" isn't available. Run: ollama pull " + model);
        }
        return new OllamaException(
                OllamaException.Reason.HTTP,
                "Ollama returned an error: " + (readable.isBlank() ? "unknown error" : readable));
    }

    /**
     * Ollama sometimes nests its error inside a JSON string. Pull out the most
     * useful text so the chat bubble does not show raw JSON.
     */
    private String readableError(String detail) {
        if (detail == null) {
            return "";
        }
        String trimmed = detail.strip();
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
                String text = error.asText().strip();
                return text.startsWith("{") ? readableError(text) : text;
            }
            JsonNode message = error.get("message");
            if (message != null && message.isTextual()) {
                return message.asText().strip();
            }
            return error.toString();
        } catch (JsonProcessingException e) {
            return trimmed;
        }
    }

    private static String readErrorBody(InputStream body) {
        try (InputStream input = body) {
            byte[] bytes = input.readNBytes(4_096);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String normalizeModelName(String name) {
        String trimmed = name == null ? "" : name.strip();
        return trimmed.contains(":") ? trimmed : trimmed + ":latest";
    }
}
