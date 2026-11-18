package com.puppy.config;

import com.puppy.document.DocumentTextExtractor;
import com.puppy.database.Database;
import com.puppy.repository.ChatRepository;
import com.puppy.repository.FocusRepository;
import com.puppy.repository.QuizRepository;
import com.puppy.repository.SettingsRepository;
import com.puppy.service.AiFocusPlanExtractor;
import com.puppy.service.AppSettingsService;
import com.puppy.service.ChatHistoryService;
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
    private final Database database;
    private final ChatHistoryService chatHistory;
    private final AppSettingsService appSettings;
    private final QuizRepository quizzes;

    private boolean started;
    private boolean closed;

    public ServiceRegistry(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.threadManager = new ThreadManager(config.appName());
        this.mascotStateService = new DefaultMascotStateService();
        this.database = Database.open(config.dataDirectory());
        try {
            var settings = new SettingsRepository(database);
            this.appSettings = new AppSettingsService(config, settings);
            this.modelService = new ModelService(
                    config,
                    new AiSettingsStore(config.dataDirectory(), settings),
                    threadManager.executor());
            this.chatHistory = new ChatHistoryService(new ChatRepository(database), settings);
            this.quizzes = new QuizRepository(database);
            DocumentTextExtractor documentTextExtractor = new DocumentTextExtractor();
            this.chatService = new ChatService(modelService, documentTextExtractor);
            this.focusService = new FocusService(
                    new AiFocusPlanExtractor(modelService, Clock.systemDefaultZone()),
                    documentTextExtractor, new FocusRepository(database));
        } catch (RuntimeException e) {
            database.close();
            threadManager.close();
            throw e;
        }
    }

    /** Initializes the services that need explicit startup. Safe to call once. */
    public void start() {
        if (started || closed) {
            return;
        }
        started = true;
        // Refresh the public model catalog in the background. A missing network
        // only leaves the cached or built-in list in place.
        modelService.refreshModelCatalogIfStale();
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

    /** Persisted Focus planner shared by the panel and chat. */
    public FocusService focusService() {
        return focusService;
    }

    public ThreadManager threadManager() {
        return threadManager;
    }

    public ChatHistoryService chatHistory() { return chatHistory; }
    public AppSettingsService appSettings() { return appSettings; }
    public QuizRepository quizzes() { return quizzes; }

    /** Releases every service; called from {@code Application.stop()}. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        threadManager.close();
        chatHistory.close();
        database.close();
    }
}
