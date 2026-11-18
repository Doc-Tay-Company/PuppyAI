package com.puppy.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenRouterModelCatalogTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void keepsOnlyTextModelsWithEnoughContextAndReadsMetadata() throws Exception {
        List<AiModel> models = OpenRouterModelCatalog.parseModels("""
                {"data":[
                  {"id":"google/gemini-2.5-flash","name":"Gemini 2.5 Flash",
                   "context_length":1048576,
                   "architecture":{"output_modalities":["text"]},
                   "pricing":{"prompt":"0.0000003","completion":"0.0000025"},
                   "supported_parameters":["temperature","response_format"]},
                  {"id":"acme/tiny-chat","name":"Tiny Chat","context_length":8192,
                   "architecture":{"output_modalities":["text"]},
                   "pricing":{"prompt":"0","completion":"0"},
                   "supported_parameters":["response_format"]},
                  {"id":"acme/image-maker","name":"Image Maker","context_length":32768,
                   "architecture":{"output_modalities":["image"]},
                   "pricing":{"prompt":"0","completion":"0"},
                   "supported_parameters":[]},
                  {"id":"acme/audio-only","name":"Audio Only","context_length":32768,
                   "architecture":{"output_modalities":["audio"]},
                   "pricing":{"prompt":"0","completion":"0"},
                   "supported_parameters":[]},
                  {"id":"acme/moderation-guard","name":"Moderation Guard","context_length":32768,
                   "architecture":{"output_modalities":["text"]},
                   "pricing":{"prompt":"0","completion":"0"},
                   "supported_parameters":[]},
                  {"id":"acme/chat-free:free","name":"Free Chat","context_length":32768,
                   "architecture":{"output_modalities":["text"]},
                   "pricing":{"prompt":"0","completion":"0"},
                   "supported_parameters":["temperature"]}
                ]}
                """);

        assertEquals(2, models.size());
        AiModel flash = models.stream()
                .filter(model -> model.modelName().equals("google/gemini-2.5-flash"))
                .findFirst()
                .orElseThrow();
        assertEquals("openrouter:google/gemini-2.5-flash", flash.id());
        assertTrue(flash.cloud());
        assertTrue(flash.supportsJson());
        assertFalse(flash.free());
        assertEquals(1_048_576, flash.contextLength());
        assertEquals("1M", flash.details().contextLabel());
        assertTrue(flash.pricingLabel().contains("/M in"));
        assertTrue(flash.pricingLabel().contains("/M out"));

        AiModel free = models.stream()
                .filter(model -> model.modelName().equals("acme/chat-free:free"))
                .findFirst()
                .orElseThrow();
        assertTrue(free.free());
        assertFalse(free.supportsJson());
        assertEquals("Free", free.pricingLabel());
    }

    @Test
    void fetchesThePublicModelListWithoutAnApiKey() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        server.createContext("/api/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, """
                    {"data":[{"id":"acme/chat","name":"Chat","context_length":32768,
                    "architecture":{"output_modalities":["text"]},
                    "pricing":{"prompt":"0.000001","completion":"0.000002"},
                    "supported_parameters":["response_format"]}]}
                    """);
        });

        OpenRouterModelCatalog catalog = new OpenRouterModelCatalog(baseUrl());
        OpenRouterModelCatalog.Catalog fetched = catalog.fetch();

        assertEquals("/api/v1/models", path.get());
        assertNull(authorization.get(), "The public model list must not send a key");
        assertEquals(1, fetched.models().size());
        assertTrue(fetched.rawJson().contains("acme/chat"));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
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
