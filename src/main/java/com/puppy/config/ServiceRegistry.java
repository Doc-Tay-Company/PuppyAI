package com.puppy.config;

import com.puppy.document.DocumentTextExtractor;
import com.puppy.service.AiFocusPlanExtractor;
import com.puppy.service.ChatService;
import com.puppy.service.DefaultMascotStateService;
import com.puppy.service.FocusService;
import com.puppy.service.MascotStateService;
import com.puppy.service.ModelService;
import com.puppy.util.ThreadManager;

import java.time.Clock;
import java.util.Objects;

/**
 * Builds and owns the application's services.
 *
 * <p>JavaFX controllers receive services from here instead of creating
 * databases, repositories or AI providers themselves. The selected AI model
 * is owned by {@link ModelService}, which reads the user's local key and model
 * choice. {@link #close()} shuts every service down when the application
 * exits.</p>
 */
public final class ServiceRegistry implements AutoCloseable {

    private final AppConfig config;
    private final ThreadManager threadManager;
    private final MascotStateService mascotStateService;
    private final ModelService modelService;
    private final ChatService chatService;
    private final FocusService focusService;

    private boolean started;
    private boolean closed;

    public ServiceRegistry(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.threadManager = new ThreadManager(config.appName());
        this.mascotStateService = new DefaultMascotStateService();
        this.modelService = new ModelService(config, new AiSettingsStore(config.dataDirectory()));
        DocumentTextExtractor documentTextExtractor = new DocumentTextExtractor();
        this.chatService = new ChatService(modelService, documentTextExtractor);
        this.focusService = new FocusService(
                new AiFocusPlanExtractor(modelService, Clock.systemDefaultZone()),
                documentTextExtractor);
    }

    /** Initializes the services that need explicit startup. Safe to call once. */
    public void start() {
        if (started || closed) {
            return;
        }
        started = true;
        // Later milestones: database connection, repositories.
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

    /** The active provider/model plus the model and key settings behind it. */
    public ModelService modelService() {
        return modelService;
    }

    /** Chat through the selected local or cloud model, including attachments. */
    public ChatService chatService() {
        return chatService;
    }

    /** In-memory Focus planner shared by the Focus panel and the chat focus option. */
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
