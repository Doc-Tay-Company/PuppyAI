package com.puppy.controller;

import com.puppy.ai.AiException;
import com.puppy.document.DocumentExtractionException;
import com.puppy.enums.MascotState;
import com.puppy.enums.MessageRole;
import com.puppy.model.ChatMessage;
import com.puppy.model.DocumentAttachment;
import com.puppy.model.FocusPlan;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.StoredMessage;
import com.puppy.model.StoredConversation;
import com.puppy.model.QuizDefinition;
import com.puppy.model.QuizAttempt;
import com.puppy.service.QuizService;
import com.puppy.service.QuizException;
import com.puppy.service.ChatHistoryService;
import com.puppy.service.ChatService;
import com.puppy.service.FocusFormats;
import com.puppy.service.FocusPlanningException;
import com.puppy.service.FocusService;
import com.puppy.service.IntentResolutionService;
import com.puppy.service.MascotStateService;
import com.puppy.ui.MarkdownRenderer;
import com.puppy.ui.QuizCard;
import com.puppy.util.DurationFormats;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/**
 * Chat panel backed by the selected AI model.
 *
 * <p>Replies stream from {@link ChatService} on the shared worker pool. All
 * JavaFX updates are marshalled back with {@link Platform#runLater}. The panel
 * presents durable history through {@link ChatHistoryService} and reads PDF/Word
 * attachments into the current conversation context.</p>
 */
public class ChatController {
    private static final ObjectMapper HISTORY_CONTEXT_JSON = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(ChatController.class.getName());

    /** What the composer option does when Send is pressed. */
    enum ComposerMode {
        CHAT("Chat"),
        FOCUS("Focus mode"),
        QUIZ("Multiple Quiz");

        private final String label;

        ComposerMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final int MAX_ATTACHMENTS = 5;
    private static final double BUBBLE_MAX_WIDTH = 640;
    private static final Duration STREAM_TICK = Duration.millis(80);
    private static final String ATTACHMENT_ONLY_PROMPT =
            "Please read the attached document and summarize the key points.";

    @FXML
    private VBox messagesBox;

    @FXML
    private VBox historyPane;

    @FXML
    private VBox historyItems;

    @FXML
    private TextField messageInput;

    @FXML
    private Button sendButton;

    @FXML
    private Button attachButton;

    @FXML
    private MenuButton actionsButton;

    @FXML
    private CheckMenuItem quizModeItem;

    @FXML
    private CheckMenuItem focusModeItem;

    private ComposerMode composerMode = ComposerMode.CHAT;

    @FXML
    private ScrollPane messageScroll;

    @FXML
    private Label thinkingLabel;

    @FXML
    private Button expandButton;

    @FXML
    private FlowPane attachmentBar;

    @FXML
    private Label attachmentStatus;

    @FXML
    private Label saveStatus;

    @FXML
    private Button retrySaveButton;

    @FXML
    private ImageView chatMascotView;

    private final List<Conversation> history = new ArrayList<>();
    private final List<Message> currentMessages = new ArrayList<>();
    private final List<DocumentAttachment> attachments = new ArrayList<>();

    private ChatService chatService;
    private FocusService focusService;
    private ExecutorService executor;
    private MascotStateService mascotStateService;
    private Runnable closeAction;
    private Runnable minimizeAction;
    private Runnable menuAction;
    private Runnable focusOpenAction;
    private BooleanSupplier expandAction;

    private boolean responding;
    private boolean extracting;
    private int selectedHistoryIndex = -1;
    private volatile int responseGeneration;
    private int attachmentGeneration;
    private Future<?> inFlight;
    private FocusPreferences pendingPreferences;
    private ChatHistoryService chatHistory;
    private QuizService quizService;
    private IntentResolutionService intentResolutionService;
    private String activeQuizId;
    private String activeRouteMessageId;
    private final java.util.Map<String, QuizJob> quizJobs = new java.util.HashMap<>();
    private final java.util.Map<String, QuizCard> quizCards = new java.util.HashMap<>();
    private String currentConversationId = java.util.UUID.randomUUID().toString();
    private final java.util.Set<String> titleRequested = new java.util.HashSet<>();
    private boolean restoring;
    private boolean preparingToQuit;
    private java.util.concurrent.CompletableFuture<Void> latestSave =
            java.util.concurrent.CompletableFuture.completedFuture(null);

    private final StringBuilder pendingTokens = new StringBuilder();
    private final StringBuilder streamingText = new StringBuilder();
    private int streamingMessageIndex = -1;
    private MessageBubble streamingBubble;
    private Label streamingLabel;

    private Timeline streamTicker;
    private PauseTransition talkingTransition;
    private PauseTransition happyTransition;

    @FXML
    public void initialize() {
        loadChatMascot();
        resetComposerMode();
        attachButton.setTooltip(new Tooltip("Attach a document"));
        streamTicker = new Timeline(new KeyFrame(STREAM_TICK, event -> flushPendingTokens()));
        streamTicker.setCycleCount(Timeline.INDEFINITE);
        updateComposerState();
    }

    private void loadChatMascot() {
        try (InputStream stream = getClass().getResourceAsStream("/icons/puppy.png")) {
            if (stream == null) {
                throw new IllegalStateException("Missing chat mascot icon: /icons/puppy.png");
            }
            chatMascotView.setImage(new Image(stream));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the chat mascot icon", e);
        }
    }

    public void attach(ChatService chatService, ExecutorService executor,
                       MascotStateService mascotStateService, FocusService focusService,
                       Runnable closeAction, Runnable minimizeAction,
                       Runnable menuAction, BooleanSupplier expandAction,
                       Runnable focusOpenAction) {
        this.chatService = Objects.requireNonNull(chatService, "chatService");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.mascotStateService = Objects.requireNonNull(mascotStateService, "mascotStateService");
        this.focusService = Objects.requireNonNull(focusService, "focusService");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.minimizeAction = Objects.requireNonNull(minimizeAction, "minimizeAction");
        this.menuAction = Objects.requireNonNull(menuAction, "menuAction");
        this.expandAction = Objects.requireNonNull(expandAction, "expandAction");
        this.focusOpenAction = Objects.requireNonNull(focusOpenAction, "focusOpenAction");
        expandButton.setText("Expand");

        talkingTransition = new PauseTransition(Duration.millis(650));
        talkingTransition.setOnFinished(event -> {
            mascotStateService.setState(MascotState.HAPPY);
            happyTransition.playFromStart();
        });
        happyTransition = new PauseTransition(Duration.millis(700));
        happyTransition.setOnFinished(event -> mascotStateService.resetToIdle());

        if (currentMessages.isEmpty()) {
            addMessage("assistant", chatService.greeting(), List.of());
        } else {
            renderMessages();
        }
        updateAttachmentBar();
        updateComposerState();
    }

    public void attachQuiz(QuizService quizService) {
        this.quizService = Objects.requireNonNull(quizService);
    }

    public void attachIntentResolution(IntentResolutionService service) {
        this.intentResolutionService = Objects.requireNonNull(service);
    }

    @FXML
    private void sendMessage() {
        if (responding || extracting || preparingToQuit) {
            return;
        }

        ComposerMode mode = composerMode;
        String raw = messageInput.getText();
        String message = raw == null ? "" : raw.strip();
        boolean action = mode != ComposerMode.CHAT;
        if (message.isEmpty() && attachments.isEmpty() && !action) {
            return;
        }

        clearTransientStatus();
        messageInput.clear();
        stopMascotReaction();

        switch (mode) {
            case QUIZ -> {
                routeRequest(message, IntentResolutionService.Preference.MULTIPLE_QUIZ);
                resetComposerMode();
                messageInput.requestFocus();
            }
            case FOCUS -> {
                if (startFocus(message)) {
                    resetComposerMode();
                }
                messageInput.requestFocus();
            }
            case CHAT -> {
                String chatMessage = message.isEmpty() ? ATTACHMENT_ONLY_PROMPT : message;
                if (handleLocalCommand(chatMessage)) {
                    messageInput.requestFocus();
                    return;
                }
                routeRequest(chatMessage, IntentResolutionService.Preference.CHAT);
            }
        }
    }

    private void routeRequest(String message, IntentResolutionService.Preference preference) {
        if (intentResolutionService == null || chatService == null || chatHistory == null) {
            showSaveStatus("AI request routing is not available. Reopen Chat and try again.", false);
            return;
        }
        String request = message == null ? "" : message.strip();
        if (preference == IntentResolutionService.Preference.MULTIPLE_QUIZ && request.isEmpty()) {
            request = "Create a quiz from the attached documents.";
        }
        if (request.isBlank() && attachments.isEmpty()) return;
        final String requestSnapshot = request;
        boolean unavailableHistoryOmitted = currentMessages.stream().anyMatch(item ->
                item.status() != StoredMessage.Status.COMPLETE || item.entryType().equals("QUIZ"));
        List<Message> priorMessageSnapshot = intentHistoryMessages(currentMessages);
        List<String> fileNames = attachments.stream().map(DocumentAttachment::fileName).toList();
        addMessage("user", requestSnapshot, fileNames);
        Message placeholder = new Message(java.util.UUID.randomUUID().toString(), "assistant", "",
                List.of(), 0L, StoredMessage.Status.GENERATING);
        currentMessages.add(placeholder);
        streamingMessageIndex = currentMessages.size() - 1;
        activeRouteMessageId = placeholder.id();
        updateSelectedConversation();

        responding = true;
        thinkingLabel.setManaged(true);
        thinkingLabel.setVisible(true);
        updateComposerState();
        mascotStateService.setState(MascotState.THINKING);
        int generation = ++responseGeneration;
        String conversationId = currentConversationId;
        List<ChatMessage> priorChatSnapshot = priorMessageSnapshot.stream().map(ChatController::toChatMessage).toList();
        List<ChatMessage> conversationSnapshot = new ArrayList<>(priorChatSnapshot);
        conversationSnapshot.add(ChatMessage.user(requestSnapshot));
        List<Message> boundedMessages = boundedIntentMessages(priorMessageSnapshot);
        List<ChatMessage> boundedHistory = boundedMessages.stream()
                .map(item -> priorChatSnapshot.get(priorMessageSnapshot.indexOf(item))).toList();
        List<IntentResolutionService.HistoryItem> historyItems = boundedMessages.stream()
                .map(messageItem -> new IntentResolutionService.HistoryItem(toChatMessage(messageItem).role(),
                        messageItem.text(), messageItem.attachments()))
                .toList();
        List<DocumentAttachment> documentSnapshot = List.copyOf(attachments);
        String instructionSnapshot = chatService.snapshotAdditionalInstructions();
        updateSelectedConversation();
        java.util.concurrent.CompletableFuture<Void> requestSave = latestSave;
        try {
            inFlight = executor.submit(() -> {
                try {
                    requestSave.join();
                    if (generation != responseGeneration || Thread.currentThread().isInterrupted()) return;
                    IntentResolutionService.Decision decision = intentResolutionService.resolve(
                            requestSnapshot, preference, historyItems, documentSnapshot, unavailableHistoryOmitted);
                    Platform.runLater(() -> completeRouting(generation, conversationId, placeholder.id(), requestSnapshot,
                            conversationSnapshot, boundedHistory, documentSnapshot, instructionSnapshot, decision));
                } catch (java.util.concurrent.CompletionException e) {
                    Platform.runLater(() -> failRouting(generation, conversationId, placeholder.id(),
                            "The request was not routed because its local save failed. Retry after checking storage."));
                } catch (AiException e) {
                    if (e.reason() != AiException.Reason.INTERRUPTED) {
                        logRoutingFailure(e.reason().name(), e);
                        Platform.runLater(() -> failRouting(generation, conversationId, placeholder.id(),
                                routingFailureMessage(e.reason())));
                    }
                } catch (RuntimeException e) {
                    logRoutingFailure("UNEXPECTED", e);
                    Platform.runLater(() -> failRouting(generation, conversationId, placeholder.id(),
                            "Puppy couldn't process the request because of a technical problem. Please try again."));
                } finally {
                    Thread.interrupted();
                }
            });
        } catch (RejectedExecutionException e) {
            failRouting(generation, conversationId, placeholder.id(), "The app is shutting down. The request was not sent.");
        }
    }

    static String routingFailureMessage(AiException.Reason reason) {
        return switch (reason) {
            case RATE_LIMIT -> "The AI service is limiting requests right now. Please wait a moment and try again.";
            case PROTOCOL -> "The AI service returned a response Puppy couldn't read. Please try again.";
            case UNAVAILABLE -> "Puppy couldn't connect to the AI service. Please check the connection and try again.";
            case TIMEOUT -> "The AI service took too long to respond. Please try again in a moment.";
            case AUTHENTICATION -> "The AI service couldn't authorize this request. Please check the API key in Settings.";
            case MODEL_NOT_FOUND -> "The selected AI model isn't available. Please choose another model in Settings.";
            case HTTP -> "The AI service couldn't complete this request. Please try again later or check the service account.";
            case INTERRUPTED -> "Request cancelled.";
        };
    }

    private static void logRoutingFailure(String category, Exception error) {
        // Do not log message/history, provider bodies, exception messages or credentials.
        LOG.log(System.Logger.Level.WARNING, "Routing failed: category={0}, exception={1}, cause={2}",
                category, error.getClass().getSimpleName(),
                error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName());
    }

    private static List<Message> intentHistoryMessages(List<Message> messages) {
        return messages.stream().filter(message -> message.status() == StoredMessage.Status.COMPLETE
                && !message.text().isBlank()
                && !"QUIZ".equals(message.entryType())).toList();
    }

    private static List<Message> boundedIntentMessages(List<Message> input) {
        List<Message> selected = new ArrayList<>();
        int chars = 0;
        for (int i = input.size() - 1; i >= 0 && selected.size() < 80; i--) {
            Message message = input.get(i);
            if (message.text().isBlank() || chars + message.text().length() > 24_000) break;
            selected.add(message);
            chars += message.text().length();
        }
        java.util.Collections.reverse(selected);
        return List.copyOf(selected);
    }

    private static ChatMessage toChatMessage(Message message) {
        MessageRole role = "user".equals(message.role()) ? MessageRole.USER : MessageRole.ASSISTANT;
        String content = message.text();
        if (!message.attachments().isEmpty()) {
            try {
                content += "\nAttachment filename metadata (untrusted JSON): "
                        + HISTORY_CONTEXT_JSON.writeValueAsString(message.attachments());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not encode attachment metadata.", e);
            }
        }
        return new ChatMessage(role, content);
    }

    private void completeRouting(int generation, String conversationId, String messageId, String request,
                                 List<ChatMessage> conversationSnapshot, List<ChatMessage> boundedHistory,
                                 List<DocumentAttachment> documentSnapshot, String instructionSnapshot,
                                 IntentResolutionService.Decision decision) {
        if (!isCurrentRoute(generation, conversationId, messageId)) return;
        activeRouteMessageId = null;
        switch (decision.outcome()) {
            case CHAT -> {
                List<ChatMessage> relevant = decision.relevantHistoryIndexes().stream()
                        .map(boundedHistory::get).toList();
                ChatService.Request chatRequest = chatService.prepareReply(conversationSnapshot,
                        documentSnapshot, relevant, instructionSnapshot);
                startChatStream(generation, messageId, chatRequest);
            }
            case CLARIFY, CONTEXT_UNAVAILABLE -> finishRoutedText(messageId, decision.reply(),
                    StoredMessage.Status.COMPLETE);
            case GENERATE_QUIZ -> {
                List<ChatMessage> relevant = decision.relevantHistoryIndexes().stream()
                        .map(boundedHistory::get).toList();
                int chars = relevant.stream().mapToInt(item -> item.content().length()).sum();
                if (chars > 8_000) {
                    finishRoutedText(messageId,
                            "The relevant discussion is larger than the quiz context limit. Please restate the key details.",
                            StoredMessage.Status.COMPLETE);
                    return;
                }
                List<DocumentAttachment> selectedDocuments = decision.requiredSourceIndexes().stream()
                        .map(documentSnapshot::get).toList();
                QuizService.Specification specification = null;
                if (decision.hasValidatedSpecification()) {
                    List<QuizDefinition.Difficulty> levels = decision.difficulties().isEmpty()
                            ? java.util.Collections.nCopies(decision.count(), QuizDefinition.Difficulty.MEDIUM)
                            : decision.difficulties().stream().map(QuizDefinition.Difficulty::valueOf).toList();
                    specification = new QuizService.Specification(decision.count(), levels);
                }
                finishRoutedText(messageId, "Quiz request confirmed.", StoredMessage.Status.COMPLETE);
                startQuiz(request, conversationId, selectedDocuments, relevant, specification,
                        decision.sourceRequired() || decision.documentRequired(), decision.topic());
            }
        }
    }

    private boolean isCurrentRoute(int generation, String conversationId, String messageId) {
        return responding && generation == responseGeneration && conversationId.equals(currentConversationId)
                && messageId.equals(activeRouteMessageId);
    }

    private void finishRoutedText(String messageId, String text, StoredMessage.Status status) {
        int index = messageIndex(messageId);
        if (index < 0) return;
        Message updated = currentMessages.get(index).withText(text, 0L, status);
        currentMessages.set(index, updated);
        addMessageNode(updated);
        updateSelectedConversation();
        refreshHistoryIfSelected();
        resetStreamingState();
        finishReplyUi(status == StoredMessage.Status.COMPLETE);
    }

    private void failRouting(int generation, String conversationId, String messageId, String error) {
        if (!isCurrentRoute(generation, conversationId, messageId)) return;
        activeRouteMessageId = null;
        finishRoutedText(messageId, error, StoredMessage.Status.ERROR);
        showSaveStatus(error, false);
    }

    private int messageIndex(String id) {
        for (int i = 0; i < currentMessages.size(); i++) {
            if (currentMessages.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private void startChatStream(int generation, String messageId, ChatService.Request request) {
        streamingMessageIndex = messageIndex(messageId);
        // The route placeholder remains the assistant bubble for the streamed reply.
        if (streamingMessageIndex < 0) {
            streamingMessageIndex = currentMessages.size() - 1;
        }
        streamingText.setLength(0);
        clearPendingTokens();
        streamTicker.playFromStart();
        long startedAtNanos = System.nanoTime();
        try {
            inFlight = executor.submit(() -> streamReply(generation, request, startedAtNanos));
        } catch (RejectedExecutionException e) {
            failReply(generation, "The app is shutting down. The request was not sent.");
        }
    }

    private void startQuiz(String request, String conversationId, List<DocumentAttachment> documents,
                           List<ChatMessage> conversationContext, QuizService.Specification specification,
                           boolean sourceGrounded, String resolvedTopic) {
        if (quizService == null || chatHistory == null) {
            finishReplyUi(false);
            showSaveStatus("Quiz generation is not available. Reopen Chat and try again.", false);
            return;
        }
        String id = java.util.UUID.randomUUID().toString();
        QuizJob job = new QuizJob(id, conversationId, request, documents, conversationContext,
                specification, sourceGrounded, resolvedTopic);
        quizJobs.put(id, job);
        currentMessages.add(new Message(id, "assistant", "Generating quiz...", List.of(), 0L,
                StoredMessage.Status.GENERATING));
        updateSelectedConversation(true);
        addMessageNode(currentMessages.getLast());
        runQuiz(job, true);
        scrollToBottom();
    }

    private void runQuiz(QuizJob job, boolean alreadyResponding) {
        if ((!alreadyResponding && responding) || extracting || preparingToQuit) {
            showSaveStatus("Finish or cancel the current request before retrying quiz generation.", false);
            return;
        }
        responding = true;
        activeQuizId = job.id;
        job.error = null;
        int generation = ++responseGeneration;
        replaceQuizMessage(job, "Generating quiz...", StoredMessage.Status.GENERATING, "TEXT");
        updateSelectedConversation();
        java.util.concurrent.CompletableFuture<Void> savedRequest = latestSave;
        refreshQuizJob(job);
        updateComposerState();
        mascotStateService.setState(MascotState.THINKING);
        try {
            inFlight = executor.submit(() -> {
                try {
                    savedRequest.join();
                    if (generation != responseGeneration || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    QuizDefinition definition = quizService.generate(job.id, job.request, job.documents,
                            job.conversationContext, job.specification, job.sourceGrounded, job.resolvedTopic);
                    Platform.runLater(() -> completeQuiz(job, definition, generation));
                } catch (QuizException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        Platform.runLater(() -> failQuiz(job, generation, e.getMessage()));
                    }
                } catch (java.util.concurrent.CompletionException e) {
                    Platform.runLater(() -> failQuiz(job, generation,
                            "Quiz generation did not start because the request could not be saved. Retry after checking local storage."));
                } catch (RuntimeException e) {
                    Platform.runLater(() -> failQuiz(job, generation,
                            "Quiz generation failed unexpectedly. Please retry generation."));
                } finally {
                    Thread.interrupted();
                }
            });
        } catch (RejectedExecutionException e) {
            failQuiz(job, generation, "The app is shutting down. Quiz generation did not start.");
        }
    }

    private void completeQuiz(QuizJob job, QuizDefinition definition, int generation) {
        if (!isCurrentQuiz(job, generation)) {
            return;
        }
        job.definition = definition;
        chatHistory.registerQuiz(definition);
        replaceQuizMessage(job, definition.title(), StoredMessage.Status.COMPLETE, "QUIZ");
        updateSelectedConversation();
        activeQuizId = null;
        refreshQuizJob(job);
        finishReplyUi(true);
        latestSave.whenComplete((unused, error) -> Platform.runLater(() -> {
            job.ready = error == null;
            job.error = error == null ? null
                    : "Quiz generated, but local saving failed. Retry save without generating again.";
            refreshQuizJob(job);
        }));
    }

    private boolean isCurrentQuiz(QuizJob job, int generation) {
        return responding && generation == responseGeneration && job.id.equals(activeQuizId)
                && job.conversationId.equals(currentConversationId);
    }

    private void failQuiz(QuizJob job, int generation, String error) {
        if (!isCurrentQuiz(job, generation)) {
            return;
        }
        job.error = error;
        replaceQuizMessage(job, "Quiz generation failed. Retry generation to try again.",
                StoredMessage.Status.ERROR, "TEXT");
        updateSelectedConversation();
        activeQuizId = null;
        refreshQuizJob(job);
        finishReplyUi(false);
    }

    private void replaceQuizMessage(QuizJob job, String text, StoredMessage.Status status, String entryType) {
        for (int i = 0; i < currentMessages.size(); i++) {
            Message old = currentMessages.get(i);
            if (old.id().equals(job.id)) {
                currentMessages.set(i, new Message(old.id(), old.role(), text, old.attachments(),
                        old.elapsedMillis(), status, entryType));
                return;
            }
        }
        throw new IllegalStateException("The active quiz entry is missing from its conversation.");
    }

    private MessageBubble addQuizNode(Message message) {
        VBox content = new VBox(8);
        content.getStyleClass().add("quiz-message");
        content.setMaxWidth(BUBBLE_MAX_WIDTH);
        messagesBox.getChildren().add(content);
        QuizJob job = quizJobs.get(message.id());
        if (job != null) {
            job.content = content;
            refreshQuizJob(job);
        } else {
            showQuizCard(content, message.id());
        }
        return new MessageBubble(content, content, content, message.text());
    }

    private void showQuizCard(VBox content, String messageId) {
        QuizDefinition definition = chatHistory.quiz(messageId).orElseThrow(
                () -> new IllegalStateException("A saved quiz definition is unavailable."));
        QuizCard card = quizCards.computeIfAbsent(messageId,
                id -> new QuizCard(new QuizAttempt(definition), () -> messageInput.requestFocus()));
        if (card.getParent() instanceof javafx.scene.layout.Pane oldParent) {
            oldParent.getChildren().remove(card);
        }
        content.getChildren().setAll(card);
    }

    private void refreshQuizJob(QuizJob job) {
        if (job.content == null) {
            return;
        }
        if (job.ready) {
            job.content.getStyleClass().remove("quiz-card");
            showQuizCard(job.content, job.id);
            return;
        }
        job.content.getChildren().clear();
        job.content.getStyleClass().remove("quiz-card");
        job.content.getStyleClass().add("quiz-card");
        Label status = new Label(job.error != null ? job.error
                : job.definition != null ? "Saving your quiz..." : "Generating quiz...");
        status.setWrapText(true);
        status.getStyleClass().add(job.error == null ? "muted-copy" : "error-copy");
        Button action = new Button(job.error != null
                ? job.definition != null ? "Retry save" : "Retry generation" : "Cancel");
        action.getStyleClass().add("quiet-button");
        action.setDisable(job.definition != null && job.error == null);
        action.setOnAction(event -> {
            if (job.definition != null) {
                action.setDisable(true);
                chatHistory.retry().whenComplete((unused, error) -> Platform.runLater(() -> {
                    job.ready = error == null;
                    job.error = error == null ? null : "Local saving still failed. Check storage and retry save.";
                    refreshQuizJob(job);
                }));
            } else if (job.error != null) {
                runQuiz(job, false);
            } else if (job.id.equals(activeQuizId)) {
                cancelResponse();
            }
        });
        if (job.definition != null && job.error == null) {
            job.content.getChildren().setAll(status);
        } else {
            job.content.getChildren().setAll(status, action);
        }
    }

    private static final class QuizJob {
        private final String id;
        private final String conversationId;
        private final String request;
        private final List<DocumentAttachment> documents;
        private final List<ChatMessage> conversationContext;
        private final QuizService.Specification specification;
        private final boolean sourceGrounded;
        private final String resolvedTopic;
        private QuizDefinition definition;
        private VBox content;
        private String error;
        private boolean ready;

        private QuizJob(String id, String conversationId, String request, List<DocumentAttachment> documents,
                        List<ChatMessage> conversationContext, QuizService.Specification specification,
                        boolean sourceGrounded, String resolvedTopic) {
            this.id = id;
            this.conversationId = conversationId;
            this.request = request;
            this.documents = List.copyOf(documents);
            this.conversationContext = List.copyOf(conversationContext);
            this.specification = specification;
            this.sourceGrounded = sourceGrounded;
            this.resolvedTopic = resolvedTopic;
        }
    }

    @FXML
    private void selectQuizMode() {
        selectComposerMode(composerMode == ComposerMode.QUIZ ? ComposerMode.CHAT : ComposerMode.QUIZ);
        messageInput.requestFocus();
    }

    @FXML
    private void selectFocusMode() {
        selectComposerMode(composerMode == ComposerMode.FOCUS ? ComposerMode.CHAT : ComposerMode.FOCUS);
        messageInput.requestFocus();
    }

    private void selectComposerMode(ComposerMode mode) {
        composerMode = mode;
        quizModeItem.setSelected(mode == ComposerMode.QUIZ);
        focusModeItem.setSelected(mode == ComposerMode.FOCUS);
        messageInput.setPromptText(switch (mode) {
            case CHAT -> "Ask me anything";
            case QUIZ -> "Describe your quiz topic or attach a document";
            case FOCUS -> "Describe what you want to study or attach a timetable";
        });
        actionsButton.setTooltip(new Tooltip(mode == ComposerMode.CHAT
                ? "Add a quiz or focus request" : mode + " selected; select again to return to Chat"));
        actionsButton.setAccessibleText("Chat actions: " + mode);
    }

    private void resetComposerMode() {
        selectComposerMode(ComposerMode.CHAT);
    }

    /**
     * Handles slash commands before a request reaches the model. The command
     * output is a normal assistant message, so it stays in the session history.
     *
     * @return {@code true} when the input was a command and needs no model reply
     */
    private boolean handleLocalCommand(String input) {
        if (input == null || !input.startsWith("/")) {
            return false;
        }
        String command = input.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if ("/focus".equals(command)) {
            startFocusCommand(input);
            return true;
        }
        if ("/quiz".equals(command)) {
            routeRequest("Create a quiz: " + input.strip().substring("/quiz".length()).strip(),
                    IntentResolutionService.Preference.CHAT);
            return true;
        }
        String reply = switch (command) {
            case "/model" -> modelCommandReply();
            case "/help" -> helpReply();
            default -> "I don't know `" + input.strip()
                    + "` yet. Use **+** for Quiz or Focus, or type a normal chat message.";
        };

        addMessage("user", input, List.of());
        addMessage("assistant", reply, List.of());
        if (mascotStateService.getState() == MascotState.THINKING
                || mascotStateService.getState() == MascotState.TALKING) {
            mascotStateService.resetToIdle();
        }
        scrollToBottom();
        return true;
    }

    private static String helpReply() {
        return """
                **Puppy options**

                Use **+** beside the attachment button, then press Send.

                - **Multiple Quiz** - create 1-20 learning questions from a topic or pinned files (default 5, Medium)
                - **Focus mode** - read your pinned files or typed subject description

                Select the same action again to return to normal Chat. Actions reset after sending.
                **/model** shows the selected model and provider; **/help** shows this list.

                Right-click a message to copy it, or use **Copy** / **Reuse** under your
                requests. **Ctrl+V** pastes into the message box.

                Switch between cloud and local models in **More > Settings**.
                Puppy chooses **Quick** or **Deep** focus from your description: subjects
                that are important, difficult or need more time become Deep focus.
                A focus plan covers one week by default; say **in 10 days** or
                **deadline 20/11** to choose a different end date.""";
    }

    /**
     * Slash-command entry point kept for keyboard users alongside the + menu.
     */
    private void startFocusCommand(String input) {
        String description = input.strip().substring("/focus".length()).strip();
        if (description.equalsIgnoreCase("quick") || description.equalsIgnoreCase("deep")) {
            addMessage("user", input, List.of());
            addMessage("assistant", """
                    Puppy now chooses **Quick** or **Deep** from your description, so there is
                    no type to force. Tell me how the subject feels. I'll start from now inside
                    your saved availability, or use a range such as **18:00-21:30** if you give one.""", List.of());
            scrollToBottom();
            mascotStateService.resetToIdle();
            return;
        }
        startFocus(description);
    }

    /**
     * Starts a focus request from the pinned files and an optional typed
     * description. Puppy infers Quick or Deep focus from that description.
     *
     * <p>An explicit free-time window overrides the saved daily availability.
     * Otherwise planning starts from now inside the saved window. Timetable
     * class times are never interpreted as free time.</p>
     *
     * @return {@code true} when the request was accepted
     */
    private boolean startFocus(String description) {
        String typed = description == null ? "" : description.strip();
        if (typed.isEmpty() && attachments.isEmpty()) {
            addMessage("user", "Focus plan", List.of());
            addMessage("assistant", focusHelpText(), List.of());
            scrollToBottom();
            mascotStateService.resetToIdle();
            return false;
        }
        if (focusService == null) {
            addMessage("user", typed.isEmpty() ? "Focus plan" : typed, List.of());
            addMessage("assistant",
                    "Focus Planner isn't available right now. Try restarting Puppy.", List.of());
            scrollToBottom();
            return false;
        }

        List<String> fileNames = attachments.stream()
                .map(DocumentAttachment::fileName)
                .toList();
        addMessage("user", typed.isEmpty() ? "Focus plan" : typed, fileNames);

        Optional<FocusPreferences> window = FocusFormats.parseFreeTimeWindow(typed);
        LocalDate planUntil = FocusFormats.parsePlanEnd(typed,
                focusService.currentTime().toLocalDate()).orElse(null);
        applyFreeTime(window.orElseGet(focusService::preferences).withPlanUntil(planUntil));
        return startFocusDocuments(typed);
    }

    /** Saves the window and confirms it before the plan is built. */
    private void applyFreeTime(FocusPreferences window) {
        pendingPreferences = window;
    }

    private void confirmFreeTime(FocusPreferences window) {
        StringBuilder confirmation = new StringBuilder("Got it - I'll schedule inside **")
                .append(FocusFormats.formatTime(window.earliest()))
                .append(" - ")
                .append(FocusFormats.formatTime(window.latest()))
                .append("**");
        if (window.planUntil() != null) {
            confirmation.append(", with the plan ending **")
                    .append(FocusFormats.formatDate(window.planUntil()))
                    .append("**");
        }
        confirmation.append(". I'll start from the current local time, rounded up to the next "
                + "five-minute slot, or the next available time if needed.");
        addMessage("assistant", confirmation.toString(), List.of());
    }

    /**
     * Runs extraction on the pinned files plus the typed description. The model
     * decides Quick or Deep focus for every task.
     */
    private boolean startFocusDocuments(String description) {
        List<DocumentAttachment> requestAttachments = new ArrayList<>(attachments);
        String typed = description == null ? "" : description.strip();
        if (!typed.isEmpty()) {
            requestAttachments.add(new DocumentAttachment(
                    Path.of("focus-request.txt"), typed, 0, false));
        }
        if (requestAttachments.isEmpty()) {
            addMessage("assistant", focusHelpText(), List.of());
            scrollToBottom();
            mascotStateService.resetToIdle();
            return false;
        }

        responding = true;
        thinkingLabel.setManaged(true);
        thinkingLabel.setVisible(true);
        updateComposerState();
        mascotStateService.setState(MascotState.THINKING);

        int generation = ++responseGeneration;
        List<DocumentAttachment> requestDocuments = List.copyOf(requestAttachments);
        FocusPreferences requestPreferences = pendingPreferences;
        pendingPreferences = null;
        java.util.concurrent.CompletableFuture<Void> savedRequest = latestSave;
        try {
            inFlight = executor.submit(() -> {
                try {
                    savedRequest.join();
                    if (generation != responseGeneration || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    if (requestPreferences != null) {
                        focusService.setPreferences(requestPreferences);
                        Platform.runLater(() -> {
                            if (generation == responseGeneration) {
                                confirmFreeTime(requestPreferences);
                            }
                        });
                    }
                    FocusPlan plan = focusService.importDocuments(requestDocuments);
                    Platform.runLater(() -> completeFocusCommand(generation, plan));
                } catch (FocusPlanningException e) {
                    String message = e.getMessage() == null || e.getMessage().isBlank()
                            ? "I couldn't build a focus plan from those files."
                            : e.getMessage();
                    Platform.runLater(() -> failFocusCommand(generation, message));
                } catch (RuntimeException e) {
                    Platform.runLater(() -> failFocusCommand(generation,
                            "Something went wrong while planning. Please try again."));
                } finally {
                    Thread.interrupted();
                }
            });
        } catch (RejectedExecutionException e) {
            responding = false;
            inFlight = null;
            thinkingLabel.setManaged(false);
            thinkingLabel.setVisible(false);
            updateComposerState();
            addMessage("assistant",
                    "I couldn't start the planner because the application is shutting down.",
                    List.of());
            mascotStateService.resetToIdle();
            return false;
        }
        return true;
    }

    private static String focusHelpText() {
        return """
                **Focus needs a file or a description**

                Pin your timetable, subject list or assignment sheet with the 📌 button, or type
                the subjects you want planned. Then choose **+ > Focus mode** and press Send.

                Tell me how the subject feels - important, difficult, or not fully mastered -
                and I'll book longer Deep focus sessions. Light review and reading stay Quick.
                If you don't mention a free-time window, I'll start from now inside your saved
                daily availability (08:00-22:00 by default). You can still give a range to override it.
                Say **in 3 days**, **for 2 weeks** or **deadline 20/11** to set the plan
                end; otherwise the plan covers one week.

                Supported files: PDF, DOCX, DOC, TXT and MD. You can also build the plan by
                hand in **Focus Planner**.""";
    }

    private void completeFocusCommand(int generation, FocusPlan plan) {
        if (generation != responseGeneration || !responding) {
            return;
        }
        addMessage("assistant", focusPlanSummary(plan), List.of());
        responding = false;
        inFlight = null;
        thinkingLabel.setManaged(false);
        thinkingLabel.setVisible(false);
        updateComposerState();
        scrollToBottom();
        if (!plan.sessions().isEmpty()) {
            mascotStateService.setState(MascotState.TALKING);
            talkingTransition.playFromStart();
            Platform.runLater(focusOpenAction);
        } else {
            mascotStateService.resetToIdle();
        }
        messageInput.requestFocus();
    }

    private void failFocusCommand(int generation, String message) {
        if (generation != responseGeneration || !responding) {
            return;
        }
        responding = false;
        inFlight = null;
        thinkingLabel.setManaged(false);
        thinkingLabel.setVisible(false);
        updateComposerState();
        addMessage("assistant", message, List.of());
        scrollToBottom();
        mascotStateService.resetToIdle();
        messageInput.requestFocus();
    }

    private static String focusPlanSummary(FocusPlan plan) {
        StringBuilder summary = new StringBuilder("**Focus plan ready**\n\n");
        if (plan.sessions().isEmpty()) {
            summary.append("I couldn't place any sessions. Check the notes below, then adjust ")
                    .append("your classes, deadlines or free time in Focus Planner.");
        } else {
            summary.append(plan.sessions().size()).append(" sessions - ")
                    .append(formatMinutes(plan.totalMinutes())).append(" planned for ")
                    .append(plan.subjectCount()).append(" subjects.\n\n");
            plan.sessions().stream().limit(8).forEach(session ->
                    summary.append("- **").append(FocusFormats.formatDate(session.date()))
                            .append(" ").append(session.timeRange()).append("** - ")
                            .append(session.subject()).append(": ")
                            .append(session.title()).append(" (")
                            .append(session.type().label()).append(")\n"));
            if (plan.sessions().size() > 8) {
                summary.append("- ...and ").append(plan.sessions().size() - 8)
                        .append(" more in Focus Planner\n");
            }
            summary.append("\nI opened **Focus Planner** so you can start, tweak or add sessions.");
        }
        if (!plan.warnings().isEmpty()) {
            summary.append("\n\n**Notes**\n");
            for (String warning : plan.warnings()) {
                summary.append("- ").append(warning).append('\n');
            }
        }
        return summary.toString().strip();
    }

    private static String formatMinutes(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + " minutes";
        }
        return rest == 0 ? hours + " hours" : hours + "h " + rest + "m";
    }

    private String modelCommandReply() {
        if (chatService == null) {
            return "I don't know which model is loaded yet.";
        }
        StringBuilder reply = new StringBuilder()
                .append("**Model:** `").append(chatService.modelName()).append("`\n\n")
                .append("**Provider:** ").append(chatService.providerName())
                .append(" (").append(chatService.isCloud() ? "cloud" : "local").append(")\n\n");
        if (!chatService.isCloud()) {
            reply.append("**Server:** ").append(chatService.connectionLabel()).append("\n\n");
        }
        reply.append("Switch models in **More > Settings**.");
        return reply.toString();
    }

    private void streamReply(int generation, ChatService.Request request, long startedAtNanos) {
        try {
            String reply = chatService.reply(request,
                    token -> enqueueToken(generation, token));
            long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            Platform.runLater(() -> completeReply(generation, reply, elapsedMillis));
        } catch (AiException e) {
            if (e.reason() == AiException.Reason.INTERRUPTED) {
                return;
            }
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? "I couldn't get a reply from the AI model."
                    : e.getMessage();
            Platform.runLater(() -> failReply(generation, message));
        } catch (RuntimeException e) {
            Platform.runLater(() -> failReply(generation,
                    "Something went wrong while talking to the AI model. Please try again."));
        } finally {
            // The worker pool is shared; never leave a cancelled request's
            // interrupt flag set for the next task on this thread.
            Thread.interrupted();
        }
    }

    private void enqueueToken(int generation, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        boolean scheduleFlush;
        synchronized (pendingTokens) {
            if (generation != responseGeneration) return;
            scheduleFlush = pendingTokens.isEmpty();
            pendingTokens.append(token);
        }
        if (scheduleFlush) {
            // Coalesce queued chunks, but don't wait for the next 80 ms animation pulse.
            Platform.runLater(() -> {
                if (generation == responseGeneration && responding) flushPendingTokens();
            });
        }
    }

    /** Drains coalesced tokens on JavaFX; the periodic ticker also provides a flush opportunity. */
    private void flushPendingTokens() {
        if (!responding) {
            clearPendingTokens();
            return;
        }

        String chunk;
        synchronized (pendingTokens) {
            if (pendingTokens.isEmpty()) {
                return;
            }
            chunk = pendingTokens.toString();
            pendingTokens.setLength(0);
        }

        if (streamingBubble == null) {
            if (streamingMessageIndex < 0) {
                currentMessages.add(new Message("assistant", "", List.of()));
                streamingMessageIndex = currentMessages.size() - 1;
            }
            streamingBubble = addMessageNode(currentMessages.get(streamingMessageIndex));
            streamingLabel = new Label();
            streamingLabel.setWrapText(true);
            streamingLabel.setMaxWidth(BUBBLE_MAX_WIDTH);
            streamingLabel.getStyleClass().add("message-text");
            streamingBubble.content().getChildren().setAll(streamingLabel);
            streamingText.setLength(0);
            thinkingLabel.setManaged(false);
            thinkingLabel.setVisible(false);
            if (mascotStateService.getState() == MascotState.THINKING) {
                mascotStateService.setState(MascotState.TALKING);
            }
        }

        streamingText.append(chunk);
        String text = streamingText.toString();
        if (streamingLabel != null) {
            streamingLabel.setText(text);
        }
        if (streamingBubble != null) {
            streamingBubble.text(text);
        }
        currentMessages.set(streamingMessageIndex,
                currentMessages.get(streamingMessageIndex).withText(text, 0L, StoredMessage.Status.GENERATING));
        messageScroll.setVvalue(1.0);
    }

    private void completeReply(int generation, String reply, long elapsedMillis) {
        if (generation != responseGeneration || !responding) {
            return;
        }
        flushPendingTokens();
        streamTicker.stop();

        String finalText = reply == null ? "" : reply.strip();
        if (finalText.isEmpty()) {
            finalText = "The model returned an empty reply. Try rephrasing your question.";
        }

        if (streamingMessageIndex >= 0 && streamingMessageIndex < currentMessages.size()) {
            currentMessages.set(streamingMessageIndex,
                    currentMessages.get(streamingMessageIndex).withText(finalText, elapsedMillis, StoredMessage.Status.COMPLETE));
            if (streamingBubble != null) {
                renderBubbleContent(streamingBubble, finalText);
                showCompletionTime(streamingBubble, elapsedMillis);
            } else {
                addMessageNode(currentMessages.get(streamingMessageIndex));
            }
            updateSelectedConversation();
            refreshHistoryIfSelected();
            scrollToBottom();
        } else {
            addMessage("assistant", finalText, List.of(), elapsedMillis);
        }

        resetStreamingState();
        finishReplyUi(true);
    }

    private void failReply(int generation, String message) {
        if (generation != responseGeneration || !responding) {
            return;
        }
        flushPendingTokens();
        streamTicker.stop();

        String error = message == null || message.isBlank()
                ? "I couldn't get a reply from the AI model."
                : message;

        if (streamingMessageIndex >= 0 && streamingMessageIndex < currentMessages.size()) {
            String partial = streamingText.toString().strip();
            currentMessages.set(streamingMessageIndex,
                    currentMessages.get(streamingMessageIndex).withText(partial, 0L, StoredMessage.Status.ERROR));
            if (streamingBubble != null) {
                renderBubbleContent(streamingBubble, partial + "\n\n" + error);
            }
            showSaveStatus(error, false);
            updateSelectedConversation();
            refreshHistoryIfSelected();
            scrollToBottom();
        } else {
            addMessage("assistant", error, List.of());
        }

        resetStreamingState();
        finishReplyUi(false);
    }

    private void finishReplyUi(boolean happy) {
        responding = false;
        inFlight = null;
        if (streamTicker != null) {
            streamTicker.stop();
        }
        thinkingLabel.setManaged(false);
        thinkingLabel.setVisible(false);
        updateComposerState();
        if (happy) {
            mascotStateService.setState(MascotState.TALKING);
            talkingTransition.playFromStart();
        } else {
            mascotStateService.resetToIdle();
        }
        messageInput.requestFocus();
    }

    private void resetStreamingState() {
        streamingMessageIndex = -1;
        streamingBubble = null;
        streamingLabel = null;
        streamingText.setLength(0);
    }

    private void clearPendingTokens() {
        synchronized (pendingTokens) {
            pendingTokens.setLength(0);
        }
    }

    private void cancelResponse() {
        boolean wasResponding = responding;
        String routingId = activeRouteMessageId;
        activeRouteMessageId = null;
        if (activeQuizId != null) {
            QuizJob job = quizJobs.get(activeQuizId);
            job.error = "Quiz generation cancelled. You can retry when ready.";
            replaceQuizMessage(job, "Quiz generation cancelled.", StoredMessage.Status.INTERRUPTED, "TEXT");
            activeQuizId = null;
            refreshQuizJob(job);
        }
        if (routingId != null) {
            int index = messageIndex(routingId);
            if (index >= 0) {
                Message interrupted = currentMessages.get(index).withText(
                        "Request cancelled before routing completed.", 0L, StoredMessage.Status.INTERRUPTED);
                currentMessages.set(index, interrupted);
                if (streamingBubble == null) addMessageNode(interrupted);
            }
        } else if (wasResponding && streamingMessageIndex >= 0 && streamingMessageIndex < currentMessages.size()) {
            flushPendingTokens();
            Message message = currentMessages.get(streamingMessageIndex);
            currentMessages.set(streamingMessageIndex,
                    message.withText(message.text(), message.elapsedMillis(), StoredMessage.Status.INTERRUPTED));
        }
        updateSelectedConversation();
        responseGeneration++;
        attachmentGeneration++;
        responding = false;
        extracting = false;
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
        if (streamTicker != null) {
            streamTicker.stop();
        }
        clearPendingTokens();
        resetStreamingState();
        thinkingLabel.setManaged(false);
        thinkingLabel.setVisible(false);
        updateComposerState();
        if (wasResponding) {
            resetMascotAfterCancel();
        }
    }

    private void resetMascotAfterCancel() {
        MascotState state = mascotStateService.getState();
        if (state == MascotState.THINKING
                || state == MascotState.TALKING
                || state == MascotState.HAPPY) {
            mascotStateService.resetToIdle();
        }
    }

    @FXML
    private void attachDocument() {
        if (responding || extracting) {
            return;
        }
        if (attachments.size() >= MAX_ATTACHMENTS) {
            showAttachmentStatus(
                    "You can attach up to " + MAX_ATTACHMENTS + " documents at a time.", true);
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach a document");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(
                        "Supported documents", "*.pdf", "*.docx", "*.doc", "*.txt", "*.md"),
                new FileChooser.ExtensionFilter("PDF documents", "*.pdf"),
                new FileChooser.ExtensionFilter("Word documents", "*.docx", "*.doc"),
                new FileChooser.ExtensionFilter("Text documents", "*.txt", "*.md"));
        File file = chooser.showOpenDialog(messagesBox.getScene().getWindow());
        if (file == null) {
            return;
        }

        Path path = file.toPath().toAbsolutePath().normalize();
        if (attachments.stream().anyMatch(document -> document.source().equals(path))) {
            showAttachmentStatus("\"" + file.getName() + "\" is already attached.", true);
            return;
        }
        extractDocument(path, file.getName());
    }

    private void extractDocument(Path path, String fileName) {
        if (chatService == null || executor == null) {
            return;
        }
        extracting = true;
        int request = ++attachmentGeneration;
        showAttachmentStatus("Reading " + fileName + "...", false);
        updateComposerState();

        try {
            executor.submit(() -> {
                try {
                    DocumentAttachment document = chatService.readDocument(path);
                    Platform.runLater(() -> {
                        if (request != attachmentGeneration) {
                            return;
                        }
                        extracting = false;
                        attachments.add(document);
                        updateSelectedConversation();
                        updateAttachmentBar();
                        hideAttachmentStatus();
                        updateComposerState();
                    });
                } catch (DocumentExtractionException e) {
                    Platform.runLater(() -> {
                        if (request != attachmentGeneration) {
                            return;
                        }
                        extracting = false;
                        updateComposerState();
                        showAttachmentStatus(e.getMessage(), true);
                    });
                } catch (RuntimeException e) {
                    Platform.runLater(() -> {
                        if (request != attachmentGeneration) {
                            return;
                        }
                        extracting = false;
                        updateComposerState();
                        showAttachmentStatus(
                                "Puppy couldn't read \"" + fileName + "\". Try another file.", true);
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            extracting = false;
            updateComposerState();
            showAttachmentStatus("The application is shutting down.", true);
        }
    }

    private void removeAttachment(DocumentAttachment document) {
        attachments.remove(document);
        updateSelectedConversation();
        updateAttachmentBar();
        if (attachments.isEmpty()) {
            hideAttachmentStatus();
        }
    }

    private void updateAttachmentBar() {
        attachmentBar.getChildren().clear();
        boolean hasAttachments = !attachments.isEmpty();
        attachmentBar.setVisible(hasAttachments);
        attachmentBar.setManaged(hasAttachments);

        for (DocumentAttachment document : attachments) {
            HBox chip = new HBox(4);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.getStyleClass().add("attachment-chip");

            Label name = new Label(document.fileName());
            name.getStyleClass().add("attachment-name");
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            name.setMaxWidth(180);
            name.setTooltip(new Tooltip(describeAttachment(document)));

            Button remove = new Button("x");
            remove.getStyleClass().add("attachment-remove");
            remove.setFocusTraversable(false);
            remove.setOnAction(event -> removeAttachment(document));

            chip.getChildren().addAll(name, remove);
            attachmentBar.getChildren().add(chip);
        }
    }

    private String describeAttachment(DocumentAttachment document) {
        StringBuilder description = new StringBuilder(document.fileName())
                .append("\n")
                .append(String.format(Locale.ROOT, "%,d", document.characterCount()))
                .append(" characters read");
        if (document.pageCount() > 0) {
            description.append(" · ").append(document.pageCount()).append(" pages");
        }
        if (document.truncated()) {
            description.append("\nTruncated to fit the model context.");
        }
        return description.toString();
    }

    private void showAttachmentStatus(String message, boolean error) {
        attachmentStatus.setText(message);
        attachmentStatus.getStyleClass().remove("error-copy");
        if (error) {
            attachmentStatus.getStyleClass().add("error-copy");
        }
        attachmentStatus.setManaged(true);
        attachmentStatus.setVisible(true);
    }

    private void hideAttachmentStatus() {
        attachmentStatus.setManaged(false);
        attachmentStatus.setVisible(false);
    }

    private void updateComposerState() {
        boolean busy = responding || extracting || preparingToQuit;
        sendButton.setDisable(busy);
        attachButton.setDisable(busy);
        actionsButton.setDisable(busy);
        if (busy) {
            actionsButton.hide();
        }
    }

    private List<ChatMessage> conversationForModel() {
        List<ChatMessage> conversation = new ArrayList<>();
        for (Message message : currentMessages) {
            if (message.text() == null || message.text().isBlank()
                    || message.status() == StoredMessage.Status.ERROR || message.entryType().equals("QUIZ")
                    || message.status() != StoredMessage.Status.COMPLETE
                    || quizJobs.containsKey(message.id())) {
                continue;
            }
            MessageRole role = "user".equals(message.role())
                    ? MessageRole.USER
                    : MessageRole.ASSISTANT;
            conversation.add(toChatMessage(message));
        }
        return conversation;
    }

    @FXML
    private void toggleHistory() {
        boolean show = !historyPane.isVisible();
        historyPane.setManaged(show);
        historyPane.setVisible(show);
        if (show) {
            refreshHistory();
        }
    }

    public void showHistory() {
        historyPane.setManaged(true);
        historyPane.setVisible(true);
        refreshHistory();
    }

    @FXML
    private void newConversation() {
        cancelResponse();
        clearTransientStatus();
        stopMascotReaction();
        if (selectedHistoryIndex < 0) {
            archiveCurrentConversation();
        }
        selectedHistoryIndex = -1;
        currentConversationId = java.util.UUID.randomUUID().toString();
        if (chatHistory != null) {
            observeSave(chatHistory.activate(""));
        }
        currentMessages.clear();
        messagesBox.getChildren().clear();
        attachments.clear();
        updateAttachmentBar();
        hideAttachmentStatus();
        resetComposerMode();
        addMessage("assistant", greeting(), List.of());
        refreshHistory();
        scrollToBottom();
        messageInput.requestFocus();
    }

    private void archiveCurrentConversation() {
        if (currentMessages.stream().noneMatch(message -> "user".equals(message.role())) && attachments.isEmpty()) {
            return;
        }
        String title = currentMessages.stream()
                .filter(message -> "user".equals(message.role()))
                .findFirst()
                .map(Message::text)
                .map(text -> text.strip().replaceAll("\\s+", " "))
                .map(text -> text.length() > 32 ? text.substring(0, 29) + "..." : text)
                .orElse("Documents");
        history.add(new Conversation(currentConversationId, title, System.currentTimeMillis(), List.copyOf(currentMessages),
                List.copyOf(attachments)));
    }

    private void selectConversation(int index) {
        if (index < 0 || index >= history.size()) {
            return;
        }
        if (index == selectedHistoryIndex) {
            scrollToBottom();
            return;
        }
        cancelResponse();
        clearTransientStatus();
        stopMascotReaction();
        if (selectedHistoryIndex < 0) {
            archiveCurrentConversation();
        } else {
            updateSelectedConversation();
        }
        selectedHistoryIndex = index;
        Conversation conversation = history.get(index);
        currentConversationId = conversation.id();
        if (chatHistory != null) {
            observeSave(chatHistory.activate(currentConversationId));
        }
        currentMessages.clear();
        currentMessages.addAll(conversation.messages());
        attachments.clear();
        attachments.addAll(conversation.attachments());
        updateAttachmentBar();
        hideAttachmentStatus();
        resetComposerMode();
        renderMessages();
        refreshHistory();
        updateComposerState();
        messageInput.requestFocus();
    }

    private void refreshHistory() {
        historyItems.getChildren().clear();
        if (history.isEmpty()) {
            Label empty = new Label("No saved chats yet.\nStart chatting and this session will appear here.");
            empty.getStyleClass().add("muted-copy");
            empty.setWrapText(true);
            historyItems.getChildren().add(empty);
            return;
        }
        List<Integer> ordered = java.util.stream.IntStream.range(0, history.size()).boxed()
                .sorted(java.util.Comparator.comparingLong((Integer i) -> history.get(i).updatedAt()).reversed()).toList();
        for (int i : ordered) {
            int index = i;
            Conversation conversation = history.get(i);
            Button item = new Button(conversation.title());
            item.getStyleClass().add("history-item");
            if (i == selectedHistoryIndex) {
                item.getStyleClass().add("active-history-item");
            }
            item.setMaxWidth(Double.MAX_VALUE);
            item.setTooltip(new Tooltip(
                    conversation.title() + "\n"
                            + conversation.messages().size() + " messages"));
            item.setOnAction(event -> selectConversation(index));
            historyItems.getChildren().add(item);
        }
    }

    private void refreshHistoryIfSelected() {
        if (selectedHistoryIndex >= 0) {
            refreshHistory();
        }
    }

    private MessageBubble addMessage(String role, String text, List<String> fileNames) {
        return addMessage(role, text, fileNames, 0L);
    }

    private MessageBubble addMessage(String role, String text, List<String> fileNames,
                                     long elapsedMillis) {
        Message message = new Message(role, text, fileNames, elapsedMillis);
        currentMessages.add(message);
        updateSelectedConversation(true);
        refreshHistoryIfSelected();
        MessageBubble bubble = addMessageNode(message);
        scrollToBottom();
        return bubble;
    }

    private void updateSelectedConversation() {
        updateSelectedConversation(false);
    }

    private void updateSelectedConversation(boolean newMessage) {
        if (restoring || preparingToQuit) {
            return;
        }
        if (selectedHistoryIndex < 0 && (!attachments.isEmpty()
                || currentMessages.stream().anyMatch(message -> "user".equals(message.role())))) {
            archiveCurrentConversation();
            selectedHistoryIndex = history.size() - 1;
        }
        if (selectedHistoryIndex < 0 || selectedHistoryIndex >= history.size()) {
            return;
        }
        Conversation existing = history.get(selectedHistoryIndex);
        long updatedAt = newMessage
                ? Math.max(System.currentTimeMillis(), history.stream()
                        .mapToLong(Conversation::updatedAt).max().orElse(0L) + 1)
                : existing.updatedAt();
        history.set(selectedHistoryIndex,
                new Conversation(existing.id(), existing.title(), updatedAt, List.copyOf(currentMessages),
                        List.copyOf(attachments)));
        if (chatHistory != null) {
            Conversation snapshot = history.get(selectedHistoryIndex);
            latestSave = chatHistory.save(toStored(snapshot));
            observeSave(latestSave);
            observeSave(chatHistory.activate(snapshot.id()));
            if (currentMessages.stream().anyMatch(message -> "user".equals(message.role())) && titleRequested.add(snapshot.id())) {
                String firstRequest = currentMessages.stream().filter(message -> "user".equals(message.role()))
                        .findFirst().orElseThrow().text();
                requestTitle(snapshot.id(), snapshot.title(), firstRequest, latestSave);
            }
        }
    }

    public void attachHistory(ChatHistoryService service) {
        chatHistory = Objects.requireNonNull(service);
        restoring = true;
        try {
            history.clear();
            for (StoredConversation chat : service.conversations()) {
                history.add(new Conversation(chat.id(), chat.title(), chat.updatedAt(), chat.messages().stream()
                        .map(message -> new Message(message.id(), message.role(), message.text(), message.attachments(),
                                message.elapsedMillis(), message.status(), message.entryType())).toList(), chat.attachments()));
                if (!"PENDING".equals(chat.titleState())) {
                    titleRequested.add(chat.id());
                }
            }
            String active = service.activeId();
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i).id().equals(active)) {
                    selectedHistoryIndex = i;
                    Conversation chat = history.get(i);
                    currentConversationId = chat.id();
                    currentMessages.clear();
                    currentMessages.addAll(chat.messages());
                    attachments.clear();
                    attachments.addAll(chat.attachments());
                    renderMessages();
                    break;
                }
            }
        } finally {
            restoring = false;
        }
        updateAttachmentBar();
        refreshHistory();
    }

    private StoredConversation toStored(Conversation chat) {
        return new StoredConversation(chat.id(), chat.title(), chat.updatedAt(), "PENDING", chat.messages().stream()
                .map(message -> new StoredMessage(message.id(), message.role(), message.text(), message.attachments(),
                        message.elapsedMillis(), message.status(), message.entryType())).toList(), chat.attachments());
    }

    private void requestTitle(String id, String provisional, String firstRequest,
                              java.util.concurrent.CompletableFuture<Void> saved) {
        try {
            executor.submit(() -> {
                try {
                    saved.join();
                    chatHistory.title(id, provisional, "REQUESTED").join();
                    String title = chatService.conversationTitle(firstRequest);
                    chatHistory.title(id, title, "COMPLETE").join();
                    Platform.runLater(() -> {
                        for (int i = 0; i < history.size(); i++) {
                            Conversation old = history.get(i);
                            if (old.id().equals(id)) {
                                history.set(i, new Conversation(id, title, old.updatedAt(), old.messages(), old.attachments()));
                            }
                        }
                        refreshHistory();
                    });
                } catch (AiException | java.util.concurrent.CompletionException e) {
                    Platform.runLater(() -> {
                        if (id.equals(currentConversationId)) {
                            showSaveStatus("Chat title could not be generated. The provisional title is retained.", false);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            showSaveStatus("The app is shutting down; the provisional chat title is retained.", false);
        }
    }

    private void observeSave(java.util.concurrent.CompletableFuture<Void> future) {
        future.whenComplete((unused, error) -> {
            if (error != null) {
                Platform.runLater(() -> showSaveStatus(
                        "Local save failed. Content is still in memory. Check storage and choose Retry save.", true));
            }
        });
    }

    private void showSaveStatus(String message, boolean retry) {
        if (!retry && retrySaveButton.isVisible()) {
            return;
        }
        saveStatus.setText(message);
        saveStatus.setVisible(true);
        saveStatus.setManaged(true);
        if (retry) {
            retrySaveButton.setVisible(true);
            retrySaveButton.setManaged(true);
        }
    }

    private void clearTransientStatus() {
        // Unsaved changes can belong to another chat and must remain retryable.
        if (retrySaveButton.isVisible()) {
            return;
        }
        saveStatus.setText("");
        saveStatus.setVisible(false);
        saveStatus.setManaged(false);
    }

    @FXML
    private void retrySave() {
        if (chatHistory == null) {
            return;
        }
        retrySaveButton.setDisable(true);
        chatHistory.retry().whenComplete((unused, error) -> Platform.runLater(() -> {
            retrySaveButton.setDisable(false);
            if (error == null) {
                quizJobs.values().stream().filter(job -> job.definition != null).forEach(job -> {
                    job.ready = true;
                    job.error = null;
                    refreshQuizJob(job);
                });
                retrySaveButton.setVisible(false);
                retrySaveButton.setManaged(false);
                showSaveStatus("Local changes saved.", false);
            } else {
                showSaveStatus("Local save still failed. Content remains in memory; retry after fixing storage.", true);
            }
        }));
    }

    private void stopMascotReaction() {
        if (talkingTransition != null) {
            talkingTransition.stop();
        }
        if (happyTransition != null) {
            happyTransition.stop();
        }
    }

    private void renderMessages() {
        messagesBox.getChildren().clear();
        for (Message message : currentMessages) {
            if (message.text().isBlank() && message.attachments().isEmpty()
                    && message.status() == StoredMessage.Status.COMPLETE && message.entryType().equals("TEXT")) {
                continue;
            }
            addMessageNode(message);
        }
        scrollToBottom();
    }

    private MessageBubble addMessageNode(Message message) {
        if (message.entryType().equals("QUIZ") || quizJobs.containsKey(message.id())) {
            return addQuizNode(message);
        }
        boolean user = "user".equals(message.role());
        VBox content = new VBox(4);
        content.getStyleClass().add("message-content");
        content.setFillWidth(true);

        VBox bubble = new VBox(content);
        bubble.setMaxWidth(BUBBLE_MAX_WIDTH);
        bubble.getStyleClass().addAll("message-bubble",
                user ? "user-bubble" : "assistant-bubble");

        VBox column = new VBox(3);
        column.setAlignment(user ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        column.getChildren().add(bubble);

        MessageBubble handle = new MessageBubble(bubble, content, column, message.text());
        installCopyMenu(handle, user);
        showCompletionTime(handle, message.elapsedMillis());

        for (String fileName : message.attachments()) {
            Label file = new Label("Attached: " + fileName);
            file.setWrapText(true);
            file.setMaxWidth(BUBBLE_MAX_WIDTH);
            file.getStyleClass().add("message-attachment");
            column.getChildren().add(file);
        }
        if (user) {
            column.getChildren().add(messageActions(handle));
        }
        if (message.status() == StoredMessage.Status.INTERRUPTED || message.status() == StoredMessage.Status.ERROR) {
            Label status = new Label(message.status() == StoredMessage.Status.INTERRUPTED
                    ? "Reply interrupted. Send a new message to continue."
                    : "Reply failed. Send a new message to retry.");
            status.getStyleClass().add("muted-copy");
            status.setWrapText(true);
            column.getChildren().add(status);
        }

        HBox row = new HBox(column);
        row.setAlignment(user ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        messagesBox.getChildren().add(row);

        renderBubbleContent(handle, message.text());
        return handle;
    }

    /** Right-click actions available on every message bubble. */
    private void installCopyMenu(MessageBubble bubble, boolean user) {
        ContextMenu menu = new ContextMenu();
        MenuItem copy = new MenuItem("Copy message");
        copy.setOnAction(event -> copyToClipboard(bubble.text()));
        MenuItem reuse = new MenuItem(user ? "Reuse this request" : "Reuse in message box");
        reuse.setOnAction(event -> reuseMessage(bubble.text()));
        menu.getItems().addAll(copy, reuse);
        bubble.box().setOnContextMenuRequested(event -> {
            menu.show(bubble.box(), event.getScreenX(), event.getScreenY());
            event.consume();
        });
        Tooltip.install(bubble.box(), new Tooltip("Right-click to copy this message"));
    }

    /** Small Copy / Reuse actions under the user's own requests. */
    private HBox messageActions(MessageBubble bubble) {
        Button copy = new Button("Copy");
        copy.getStyleClass().add("message-action");
        copy.setFocusTraversable(false);
        copy.setOnAction(event -> copyFromButton(copy, bubble.text()));

        Button reuse = new Button("Reuse");
        reuse.getStyleClass().add("message-action");
        reuse.setFocusTraversable(false);
        reuse.setOnAction(event -> reuseMessage(bubble.text()));

        HBox actions = new HBox(4, copy, reuse);
        actions.getStyleClass().add("message-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);
        return actions;
    }

    private void copyFromButton(Button source, String text) {
        if (!copyToClipboard(text)) {
            return;
        }
        String previous = source.getText();
        source.setText("Copied");
        PauseTransition reset = new PauseTransition(Duration.millis(1_200));
        reset.setOnFinished(event -> source.setText(previous));
        reset.play();
    }

    private static boolean copyToClipboard(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        return true;
    }

    /** Puts a previous request back into the composer without sending it. */
    private void reuseMessage(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        messageInput.setText(text);
        messageInput.positionCaret(text.length());
        messageInput.requestFocus();
    }

    /** Adds the small "Completed in" footer under a finished model reply. */
    private void showCompletionTime(MessageBubble bubble, long elapsedMillis) {
        if (bubble == null || elapsedMillis <= 0) {
            return;
        }
        boolean alreadyShown = bubble.column().getChildren().stream()
                .anyMatch(node -> node.getStyleClass().contains("message-timing"));
        if (alreadyShown) {
            return;
        }
        Label timing = new Label("Completed in: " + DurationFormats.formatElapsed(elapsedMillis));
        timing.getStyleClass().add("message-timing");
        bubble.column().getChildren().add(timing);
    }

    private void renderBubbleContent(MessageBubble bubble, String text) {
        bubble.text(text);
        bubble.content().getChildren().clear();
        if (text == null || text.isBlank()) {
            return;
        }
        Node rendered = MarkdownRenderer.render(text);
        bubble.content().getChildren().add(rendered);
    }

    private void scrollToBottom() {
        messageScroll.setVvalue(1.0);
        Platform.runLater(() -> messageScroll.setVvalue(1.0));
    }

    private String greeting() {
        return chatService == null
                ? "Hi! I'm Puppy, your desktop companion. Ask me anything."
                : chatService.greeting();
    }

    @FXML
    private void onClose() {
        closeAction.run();
    }

    @FXML
    private void onMinimize() {
        minimizeAction.run();
    }

    @FXML
    private void onMainMenu() {
        menuAction.run();
    }

    @FXML
    private void toggleExpanded() {
        boolean expanded = expandAction.getAsBoolean();
        expandButton.setText(expanded ? "Restore" : "Expand");
    }

    /** Called by the shell when the panel is collapsed or reopened. */
    public void setExpanded(boolean expanded) {
        if (expandButton != null) {
            expandButton.setText(expanded ? "Restore" : "Expand");
        }
    }

    public void close() {
        cancelResponse();
        stopMascotReaction();
        if (streamTicker != null) {
            streamTicker.stop();
        }
    }

    public boolean isResponding() {
        return responding;
    }

    public MascotState activityState() {
        return streamingBubble == null ? MascotState.THINKING : MascotState.TALKING;
    }

    public void prepareToQuit() {
        close();
        preparingToQuit = true;
        updateComposerState();
    }

    public void cancelQuit() {
        preparingToQuit = false;
        updateComposerState();
    }

    private static final class MessageBubble {

        private final VBox box;
        private final VBox content;
        private final VBox column;
        private String text;

        private MessageBubble(VBox box, VBox content, VBox column, String text) {
            this.box = box;
            this.content = content;
            this.column = column;
            this.text = text == null ? "" : text;
        }

        private VBox box() {
            return box;
        }

        private VBox content() {
            return content;
        }

        private VBox column() {
            return column;
        }

        private String text() {
            return text;
        }

        private void text(String value) {
            text = value == null ? "" : value;
        }
    }

    private record Message(String id, String role, String text, List<String> attachments,
                           long elapsedMillis, StoredMessage.Status status, String entryType) {

        private Message {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(text, "text");
            attachments = List.copyOf(attachments);
            if (elapsedMillis < 0) {
                throw new IllegalArgumentException("elapsedMillis must not be negative");
            }
        }

        private Message(String role, String text, List<String> attachments) {
            this(role, text, attachments, 0L);
        }

        private Message(String role, String text, List<String> attachments, long elapsedMillis) {
            this(java.util.UUID.randomUUID().toString(), role, text, attachments, elapsedMillis, StoredMessage.Status.COMPLETE);
        }

        private Message(String id, String role, String text, List<String> attachments,
                        long elapsedMillis, StoredMessage.Status status) {
            this(id, role, text, attachments, elapsedMillis, status, "TEXT");
        }

        private Message withText(String text, long elapsedMillis, StoredMessage.Status status) {
            return new Message(id, role, text, attachments, elapsedMillis, status, entryType);
        }
    }

    private record Conversation(String id, String title, long updatedAt, List<Message> messages,
                                List<DocumentAttachment> attachments) {

        private Conversation {
            messages = List.copyOf(messages);
            attachments = List.copyOf(attachments);
        }
    }
}
