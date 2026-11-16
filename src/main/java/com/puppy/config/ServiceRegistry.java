package com.puppy.config;

import com.puppy.ai.OllamaClient;
import com.puppy.document.DocumentTextExtractor;
import com.puppy.service.ChatService;
import com.puppy.service.DefaultMascotStateService;
import com.puppy.service.FocusService;
import com.puppy.service.MascotStateService;
import com.puppy.service.OllamaFocusPlanExtractor;
import com.puppy.util.ThreadManager;

import java.time.Clock;
import java.util.Objects;

/**
 * Builds and owns the application's services.
 *
 * <p>JavaFX controllers receive services from here instead of creating databases,
 * repositories or AI providers themselves. Later milestones register persistence,
 * AI providers and research services in the same place, and {@link #close()}
 * shuts all of them down when the application exits.</p>
 */
public final class ServiceRegistry implements AutoCloseable {

    private final AppConfig config;
    private final ThreadManager threadManager;
    private final MascotStateService mascotStateService;
    private final ChatService chatService;
    private final FocusService focusService;

    private boolean started;
    private boolean closed;

    public ServiceRegistry(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.threadManager = new ThreadManager(config.appName());
        this.mascotStateService = new DefaultMascotStateService();
        OllamaClient ollamaClient = new OllamaClient(config.ollamaUrl(), config.ollamaModel());
        DocumentTextExtractor documentTextExtractor = new DocumentTextExtractor();
        this.chatService = new ChatService(ollamaClient, documentTextExtractor);
        this.focusService = new FocusService(
                new OllamaFocusPlanExtractor(ollamaClient, Clock.systemDefaultZone()),
                documentTextExtractor);
    }

    /** Initializes the services that need explicit startup. Safe to call once. */
    public void start() {
        if (started || closed) {
            return;
        }
        started = true;
        // Later milestones: database connection, repositories, cloud AI providers.
    }

    public boolean isStarted() {
        return started;
    }

    public AppConfig config() {
        return config;
    }

    public MascotStateService mascotStateService() {
        return mascotStateService;
    }

    /**
     * Local chat through Ollama, including document attachment extraction.
     * AI providers for online modes will be registered here later.
     */
    public ChatService chatService() {
        return chatService;
    }

    /** In-memory Focus planner shared by the Focus panel and the /focus chat command. */
    public FocusService focusService() {
        return focusService;
    }

    public ThreadManager threadManager() {
        return threadManager;
    }

    /** Releases every service; called from {@code Application.stop()}. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        threadManager.close();
    }
}
