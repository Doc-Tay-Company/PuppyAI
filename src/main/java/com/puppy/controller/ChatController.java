package com.puppy.controller;

import com.puppy.ai.AiException;
import com.puppy.document.DocumentExtractionException;
import com.puppy.enums.MascotState;
import com.puppy.enums.MessageRole;
import com.puppy.model.ChatMessage;
import com.puppy.model.DocumentAttachment;
import com.puppy.model.FocusContext;
import com.puppy.model.FocusEditResult;
import com.puppy.model.FocusPlan;
import com.puppy.model.FocusPlanDraft;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.StoredMessage;
import com.puppy.model.StoredConversation;
import com.puppy.service.AiFocusPlanEditor;
import com.puppy.service.ChatHistoryService;
import com.puppy.service.ChatService;
import com.puppy.service.FocusFormats;
import com.puppy.service.FocusPlanningException;
import com.puppy.service.FocusService;
import com.puppy.service.MascotStateService;
import com.puppy.ui.MarkdownRenderer;
import com.puppy.util.DurationFormats;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
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

    /**
     * What the composer option does when Send is pressed.
     *
     * <p>Exactly one option is active at a time. The selected option stays
     * active after a reply; choosing another option replaces it.</p>
     */
    enum ComposerMode {
        CHAT("Chat"),
        FOCUS("Study"),
        ADJUST("Adjust plan"),
        HELP("Help");

        private final String label;

        ComposerMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * A focus request waiting for the user's free-time window. The typed
     * description and the plan end are kept so the plan can start as soon as
     * the window arrives.
     */
    private record PendingFocus(String description, LocalDate planUntil) {

        private PendingFocus withPlanUntil(LocalDate value) {
            return new PendingFocus(description, value);
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
    private ComboBox<ComposerMode> modeChoice;

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
    private AiFocusPlanEditor focusPlanEditor;
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
    private PendingFocus pendingFocus;
    private FocusPlanDraft pendingFocusDraft;
    private HBox pendingDraftActions;
    private FocusPreferences pendingPreferences;
    private ChatHistoryService chatHistory;
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
        modeChoice.getItems().setAll(ComposerMode.values());
        modeChoice.setValue(ComposerMode.CHAT);
        modeChoice.setTooltip(new Tooltip(
                "Choose what Send should do; it stays selected until you choose another option"));
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
        this.focusPlanEditor = new AiFocusPlanEditor(chatService.modelService());
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

    @FXML
    private void sendMessage() {
        if (responding || extracting) {
            return;
        }

        ComposerMode mode = modeChoice.getValue() == null
                ? ComposerMode.CHAT : modeChoice.getValue();
        String raw = messageInput.getText();
        String message = raw == null ? "" : raw.strip();
        boolean action = mode != ComposerMode.CHAT;
        if (message.isEmpty() && attachments.isEmpty() && !action) {
            return;
        }

        messageInput.clear();
        stopMascotReaction();

        if (pendingFocusDraft != null && !message.isEmpty() && !message.startsWith("/")) {
            handlePendingFocusDraft(message);
            messageInput.requestFocus();
            return;
        }

        if (pendingFocus != null && !message.isEmpty() && !message.startsWith("/")) {
            answerPendingFocus(message);
            messageInput.requestFocus();
            return;
        }

        // Slash commands work regardless of the selected feature, so `/model`
        // and `/help` never get treated as a Focus description.
        if (message.startsWith("/") && handleLocalCommand(message)) {
            messageInput.requestFocus();
            return;
        }

        switch (mode) {
            case FOCUS -> {
                startFocus(message);
                messageInput.requestFocus();
            }
            case ADJUST -> {
                startAdjustPlan(message);
                messageInput.requestFocus();
            }
            case HELP -> {
                answerLocalCommand(ComposerMode.HELP, helpReply());
                messageInput.requestFocus();
            }
            case CHAT -> {
                String chatMessage = message.isEmpty() ? ATTACHMENT_ONLY_PROMPT : message;
                boolean hasPlan = focusService != null && !focusService.sessions().isEmpty();
                if (hasPlan && (isPlanDropSubjectIntent(chatMessage, focusService.subjects())
                        || isPlanHorizonIntent(chatMessage)
                        || isPlanPriorityIntent(chatMessage, focusService.subjects())
                        || isPlanEditIntent(chatMessage, focusService.sessions()))) {
                    addMessage("user", chatMessage, List.of());
                    startEditFocus(chatMessage);
                    messageInput.requestFocus();
                    return;
                }
                sendChat(chatMessage);
            }
        }
    }

    private void sendChat(String message) {
        List<String> fileNames = attachments.stream()
                .map(DocumentAttachment::fileName)
                .toList();

        addMessage("user", message, fileNames);
        Message placeholder = new Message(java.util.UUID.randomUUID().toString(), "assistant", "",
                List.of(), 0L, StoredMessage.Status.GENERATING);
        currentMessages.add(placeholder);
        streamingMessageIndex = currentMessages.size() - 1;
        updateSelectedConversation();
        java.util.concurrent.CompletableFuture<Void> savedRequest = latestSave;
        responding = true;
        thinkingLabel.setManaged(true);
        thinkingLabel.setVisible(true);
        updateComposerState();
        mascotStateService.setState(MascotState.THINKING);

        int generation = ++responseGeneration;
        clearPendingTokens();
        streamTicker.playFromStart();
        List<ChatMessage> conversation = conversationForModel();
        List<DocumentAttachment> requestAttachments = List.copyOf(attachments);
        long startedAtNanos = System.nanoTime();

        try {
            inFlight = executor.submit(
                    () -> {
                        try {
                            savedRequest.join();
                            if (generation == responseGeneration && !Thread.currentThread().isInterrupted()) {
                                streamReply(generation, conversation, requestAttachments, startedAtNanos);
                            }
                        } catch (java.util.concurrent.CompletionException e) {
                            Platform.runLater(() -> failReply(generation,
                                    "The request was not sent because its local save failed. Use Retry save, then send again."));
                        }
                    });
        } catch (RejectedExecutionException e) {
            responding = false;
            inFlight = null;
            streamTicker.stop();
            thinkingLabel.setManaged(false);
            thinkingLabel.setVisible(false);
            updateComposerState();
            addMessage("assistant",
                    "I couldn't start a reply because the application is shutting down.", List.of());
            mascotStateService.resetToIdle();
        }
    }

    private void answerLocalCommand(ComposerMode mode, String reply) {
        addMessage("user", mode.toString(), List.of());
        addMessage("assistant", reply, List.of());
        if (mascotStateService.getState() == MascotState.THINKING
                || mascotStateService.getState() == MascotState.TALKING) {
            mascotStateService.resetToIdle();
        }
        scrollToBottom();
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
        String reply = switch (command) {
            case "/model" -> modelCommandReply();
            case "/help" -> helpReply();
            default -> "I don't know `" + input.strip()
                    + "` yet. Choose an option next to the message box, or try Chat.";
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

                Choose one option next to the message box, then press Send. The option
                stays selected until you choose another one.

                - **Study** - read your pinned files or typed subject description
                - **Adjust plan** - change times, durations or subjects in the saved plan
                - **Help** - show this list

                **Chat** sends everything else to the selected model.
                Type **/model** to see the selected model and provider.

                Right-click a message to copy it, or use **Copy** / **Reuse** under your
                requests. **Ctrl+V** pastes into the message box.

                Switch between cloud and local models in **More > Settings**.
                Puppy gives every subject a review session and adds 50% extra time
                (at least 30 minutes) to subjects you say are important, difficult or
                need more time, so the other subjects are still scheduled.
                A plan covers one week by default; say **in 10 days** or
                **deadline 20/11** to choose a different end date.""";
    }

    /**
     * Slash-command entry point kept for keyboard users. The option next to the
     * message box is the normal path now.
     */
    private void startFocusCommand(String input) {
        String description = input.strip().substring("/focus".length()).strip();
        if (description.equalsIgnoreCase("quick") || description.equalsIgnoreCase("deep")) {
            addMessage("user", input, List.of());
            addMessage("assistant", """
                    Tell me the subjects to include or attach a timetable, and I'll build a
                    **plan**. You can also give a free-time window, for example
                    **18:00-21:30**.""", List.of());
            scrollToBottom();
            mascotStateService.resetToIdle();
            return;
        }
        startFocus(description);
    }

    /**
     * Starts a Study request from the pinned files and an optional typed
     * description.
     *
     * <p>The planner only runs once a free-time window is known. The window is
     * parsed from the typed description, never from a pinned timetable, because
     * class times are not free time. When no range is present, Puppy keeps the
     * request pending and asks for the window before opening Study.</p>
     *
     * @return {@code true} when the request was accepted
     */
    private boolean startFocus(String description) {
        String typed = description == null ? "" : description.strip();
        if (typed.isEmpty() && attachments.isEmpty()) {
            addMessage("user", "Study", List.of());
            addMessage("assistant", focusHelpText(), List.of());
            scrollToBottom();
            mascotStateService.resetToIdle();
            return false;
        }
        if (focusService == null) {
            addMessage("user", typed.isEmpty() ? "Study" : typed, List.of());
            addMessage("assistant",
                    "Study isn't available right now. Try restarting Puppy.", List.of());
            scrollToBottom();
            return false;
        }

        List<String> fileNames = attachments.stream()
                .map(DocumentAttachment::fileName)
                .toList();
        addMessage("user", typed.isEmpty() ? "Study" : typed, fileNames);

        boolean hasExistingPlan = !focusService.sessions().isEmpty();
        boolean hasAttachments = !attachments.isEmpty();
        if (hasExistingPlan && !hasAttachments
                && (isPlanDropSubjectIntent(typed, focusService.subjects())
                || isPlanHorizonIntent(typed)
                || isPlanPriorityIntent(typed, focusService.subjects())
                || isPlanEditIntent(typed, focusService.sessions()))) {
            return startEditFocus(typed);
        }

        Optional<FocusPreferences> window = FocusFormats.parseFreeTimeWindow(typed);
        LocalDate planUntil = FocusFormats.parsePlanEnd(typed, LocalDate.now()).orElse(null);
        if (window.isPresent()) {
            applyFreeTime(window.get().withPlanUntil(planUntil));
            return startFocusDocuments(typed);
        }

        if (focusService.preferencesConfigured()) {
            // The user already chose a window; reuse it instead of asking again.
            FocusPreferences existing = focusService.preferences();
            applyFreeTime(existing.withPlanUntil(planUntil));
            return startFocusDocuments(typed);
        }

        pendingFocus = new PendingFocus(typed, planUntil);
        addMessage("assistant", freeTimeQuestion(), List.of());
        scrollToBottom();
        mascotStateService.resetToIdle();
        return true;
    }

    /**
     * Handles the reply to {@link #freeTimeQuestion()}: parse a range, save it
     * as the planner's free time, then continue with the pending request.
     */
    private void answerPendingFocus(String message) {
        if (pendingFocus == null) {
            return;
        }
        if (isCancelReply(message)) {
            pendingFocus = null;
            addMessage("user", message, List.of());
            addMessage("assistant",
                    "Okay, I cancelled that plan. Send a new description whenever you're ready.",
                    List.of());
            scrollToBottom();
            mascotStateService.resetToIdle();
            return;
        }

        Optional<FocusPreferences> window = FocusFormats.parseFreeTimeWindow(message);
        Optional<LocalDate> answeredEnd = FocusFormats.parsePlanEnd(message, LocalDate.now());
        if (window.isEmpty()) {
            if (answeredEnd.isPresent() && pendingFocus != null) {
                pendingFocus = pendingFocus.withPlanUntil(answeredEnd.get());
            }
            addMessage("user", message, List.of());
            addMessage("assistant", freeTimeRetry(), List.of());
            scrollToBottom();
            mascotStateService.resetToIdle();
            return;
        }

        PendingFocus pending = pendingFocus;
        pendingFocus = null;
        addMessage("user", message, List.of());
        LocalDate planUntil = answeredEnd.orElse(pending.planUntil());
        applyFreeTime(window.get().withPlanUntil(planUntil));
        startFocusDocuments(pending.description());
        messageInput.requestFocus();
    }

    /** Saves the window and confirms it before the plan is built. */
    private void applyFreeTime(FocusPreferences window) {
        pendingPreferences = window;
    }

    /**
     * Runs extraction on the pinned files plus the typed description. The model
     * extracts the timetable, tasks and subjects; the planner does the scheduling.
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
                    FocusPlanDraft draft = focusService.previewDocuments(
                            requestDocuments, requestPreferences);
                    Platform.runLater(() -> completeFocusPreview(generation, draft,
                            requestPreferences));
                } catch (FocusPlanningException e) {
                    String message = e.getMessage() == null || e.getMessage().isBlank()
                            ? "I couldn't build a plan from those files."
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

    private static boolean isCancelReply(String message) {
        String key = message.toLowerCase(Locale.ROOT).strip();
        return key.equals("cancel") || key.equals("huy") || key.equals("hủy")
                || key.equals("huỷ") || key.equals("thoi") || key.equals("thôi")
                || key.equals("skip") || key.startsWith("khong can")
                || key.startsWith("không cần");
    }

    private static String freeTimeQuestion() {
        return """
                **When are you free?**

                Tell me a start and end time for your study window, for example
                **18:00-21:30**, **7h-9h** or **7pm-9pm**. I'll fit the plan inside it and
                avoid your classes.""";
    }

    private static String freeTimeRetry() {
        return """
                I couldn't find a start and end time. Reply with a window such as
                **18:00-21:30**, **7h-9h** or **7pm-9pm**, or say **cancel**.""";
    }

    private static String focusHelpText() {
        return """
                **Study needs a file or a description**

                Pin your timetable, subject list or assignment sheet with the 📌 button, or type
                the subjects you want planned. Then choose **Study** and press Send.

                Tell me which subject needs more time - important, difficult, or not fully
                mastered - and I'll lengthen its tasks without dropping the other subjects.
                If you don't mention a free-time window, I'll ask for one before planning.
                Say **in 3 days**, **for 2 weeks** or **deadline 20/11** to set the plan
                end; otherwise the plan covers one week.

                Supported files: PDF, DOCX, DOC, TXT and MD. You can also build the plan by
                hand in **Study**.""";
    }

    private void completeFocusPreview(int generation, FocusPlanDraft draft,
                                      FocusPreferences requestedPreferences) {
        if (generation != responseGeneration || !responding) {
            return;
        }
        pendingFocusDraft = draft;
        StringBuilder reply = new StringBuilder();
        if (requestedPreferences != null) {
            reply.append("I used the requested study window **")
                    .append(FocusFormats.formatTime(requestedPreferences.earliest()))
                    .append(" - ").append(FocusFormats.formatTime(requestedPreferences.latest()))
                    .append("** for this preview.\n\n");
        }
        reply.append("## Study preview\n\n")
                .append(focusPlanSummary(draft.plan()))
                .append("\n\nNothing has been saved yet. Choose **Save plan** or **Cancel** below, ")
                .append("or describe another change to refine this preview.");
        MessageBubble bubble = addMessage("assistant", reply.toString(), List.of());
        showDraftActions(bubble);
        finishFocusResponse(false);
    }

    private void handlePendingFocusDraft(String message) {
        addMessage("user", message, List.of());
        if (isCancelReply(message)) {
            cancelPendingFocusDraft();
            return;
        }
        if (isConfirmReply(message)) {
            confirmPendingFocusDraft();
            return;
        }
        startEditFocus(message);
    }

    private static boolean isConfirmReply(String message) {
        String key = FocusFormats.normalize(message);
        return key.equals("confirm") || key.equals("save") || key.equals("accept")
                || key.equals("ok") || key.equals("yes") || key.equals("dong y")
                || key.equals("xac nhan") || key.equals("luu")
                || key.equals("luu ke hoach");
    }

    private void confirmPendingFocusDraft() {
        FocusPlanDraft draft = pendingFocusDraft;
        if (draft == null) {
            return;
        }
        setDraftActionsBusy(true);
        responding = true;
        thinkingLabel.setManaged(true);
        thinkingLabel.setVisible(true);
        updateComposerState();
        mascotStateService.setState(MascotState.THINKING);
        int generation = ++responseGeneration;
        try {
            inFlight = executor.submit(() -> {
                try {
                    FocusPlan saved = focusService.confirmDraft(draft);
                    Platform.runLater(() -> completeDraftConfirmation(generation, saved));
                } catch (FocusPlanningException | RuntimeException e) {
                    Platform.runLater(() -> failFocusCommand(generation,
                            e.getMessage() == null ? "The plan could not be saved." : e.getMessage()));
                }
            });
        } catch (RejectedExecutionException e) {
            failFocusCommand(generation, "The app is shutting down; the plan was not saved.");
        }
    }

    private void cancelPendingFocusDraft() {
        if (pendingFocusDraft == null) {
            return;
        }
        pendingFocusDraft = null;
        clearDraftActions();
        addMessage("assistant", "Preview cancelled. Your saved plan was not changed.",
                List.of());
        scrollToBottom();
        mascotStateService.resetToIdle();
        messageInput.requestFocus();
    }

    /** Adds the two explicit preview actions directly below the assistant reply. */
    private void showDraftActions(MessageBubble bubble) {
        clearDraftActions();
        Button save = new Button("Save plan");
        save.getStyleClass().add("primary-button");
        save.setOnAction(event -> confirmPendingFocusDraft());
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("quiet-button");
        cancel.setOnAction(event -> cancelPendingFocusDraft());
        HBox actions = new HBox(8, save, cancel);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("draft-actions");
        pendingDraftActions = actions;
        bubble.column().getChildren().add(actions);
        scrollToBottom();
    }

    private void clearDraftActions() {
        if (pendingDraftActions == null) {
            return;
        }
        if (pendingDraftActions.getParent() instanceof VBox parent) {
            parent.getChildren().remove(pendingDraftActions);
        }
        pendingDraftActions = null;
    }

    private void setDraftActionsBusy(boolean busy) {
        if (pendingDraftActions == null) {
            return;
        }
        pendingDraftActions.getChildren().forEach(node -> node.setDisable(busy));
    }

    private void completeDraftConfirmation(int generation, FocusPlan saved) {
        if (generation != responseGeneration || !responding) {
            return;
        }
        pendingFocusDraft = null;
        clearDraftActions();
        MessageBubble bubble = addMessage("assistant",
                "Plan saved with **" + saved.sessions().size()
                        + " sessions**. Sessions remain pending until you start them.", List.of());
        Button open = new Button("Open Study");
        open.getStyleClass().add("primary-button");
        open.setOnAction(event -> focusOpenAction.run());
        bubble.column().getChildren().add(open);
        finishFocusResponse(false);
    }

    private void finishFocusResponse(boolean openFocus) {
        responding = false;
        inFlight = null;
        thinkingLabel.setManaged(false);
        thinkingLabel.setVisible(false);
        updateComposerState();
        scrollToBottom();
        mascotStateService.setState(MascotState.TALKING);
        talkingTransition.playFromStart();
        if (openFocus) {
            Platform.runLater(focusOpenAction);
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
        setDraftActionsBusy(false);
        updateComposerState();
        addMessage("assistant", message, List.of());
        scrollToBottom();
        mascotStateService.resetToIdle();
        messageInput.requestFocus();
    }

    /**
     * The explicit "Adjust plan" composer mode: describe a change in natural
     * language and let the AI plan editor turn it into structured edits.
     */
    private void startAdjustPlan(String message) {
        String typed = message == null ? "" : message.strip();
        List<String> fileNames = attachments.stream()
                .map(DocumentAttachment::fileName)
                .toList();

        if (focusService == null) {
            addMessage("user", typed.isEmpty() ? "Adjust plan" : typed, fileNames);
            addMessage("assistant",
                    "Study isn't available right now. Try restarting Puppy.", List.of());
            scrollToBottom();
            return;
        }

        boolean hasPlan = !focusService.sessions().isEmpty();
        if (!hasPlan) {
            addMessage("user", typed.isEmpty() ? "Adjust plan" : typed, fileNames);
            addMessage("assistant", """
                    **There is no plan to adjust yet**

                    Choose **Study** to build one from your files or a description
                    first, then use **Adjust plan** to move, resize or rename sessions.""",
                    List.of());
            scrollToBottom();
            mascotStateService.resetToIdle();
            return;
        }
        if (typed.isEmpty()) {
            addMessage("user", "Adjust plan", fileNames);
            addMessage("assistant", adjustHelpText(), List.of());
            scrollToBottom();
            mascotStateService.resetToIdle();
            return;
        }

        addMessage("user", typed, fileNames);
        startEditFocus(typed);
    }

    private static String adjustHelpText() {
        return """
                **Adjust your plan**

                Choose **Adjust plan**, describe the change, then press Send. For example:

                - "Move Math to tomorrow at 19:00"
                - "Make the Physics sessions 75 minutes"
                - "Change Wednesday's session to Chemistry"
                - "Delete the Friday review"
                - "Add a Chemistry session on Saturday at 9:00"
                - "My free time is 18:00-22:00 from now on"

                Every change is checked against your classes, your free-time
                window and the rest of the plan before it is saved. You can undo
                the last change in **Study**.""";
    }

    private boolean startEditFocus(String description) {
        responding = true;
        thinkingLabel.setManaged(true);
        thinkingLabel.setVisible(true);
        updateComposerState();
        mascotStateService.setState(MascotState.THINKING);

        int generation = ++responseGeneration;
        java.util.concurrent.CompletableFuture<Void> savedRequest = latestSave;
        try {
            inFlight = executor.submit(() -> {
                try {
                    savedRequest.join();
                    if (generation != responseGeneration || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    FocusPlanDraft currentDraft = pendingFocusDraft;
                    FocusContext context = currentDraft == null
                            ? new FocusContext(focusService.currentPlan(), focusService.classes(),
                                    focusService.preferences(), focusService.preferencesConfigured())
                            : new FocusContext(currentDraft.proposed().plan(),
                                    currentDraft.proposed().classes().stream()
                                            .map(com.puppy.model.FocusState.ClassEntry::value).toList(),
                                    currentDraft.proposed().preferences(),
                                    currentDraft.proposed().preferencesConfigured());
                    FocusEditResult result = focusPlanEditor.edit(context, description);
                    FocusPlanDraft preview = result.isEmpty() ? currentDraft
                            : currentDraft == null
                                    ? focusService.previewEdits(result.actions(), result.summary())
                                    : focusService.previewEdits(currentDraft, result.actions(), result.summary());
                    Platform.runLater(() -> completeFocusEdit(generation, result, preview));
                } catch (FocusPlanningException e) {
                    String message = e.getMessage() == null || e.getMessage().isBlank()
                            ? "I couldn't edit the plan based on your request."
                            : e.getMessage();
                    Platform.runLater(() -> failFocusCommand(generation, message));
                } catch (RuntimeException e) {
                    Platform.runLater(() -> failFocusCommand(generation,
                            "Something went wrong while editing your plan. Please try again."));
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
                    "I couldn't edit the plan because the application is shutting down.",
                    List.of());
            mascotStateService.resetToIdle();
            return false;
        }
        return true;
    }

    private void completeFocusEdit(int generation, FocusEditResult result,
                                   FocusPlanDraft preview) {
        if (generation != responseGeneration || !responding) {
            return;
        }
        StringBuilder reply = new StringBuilder();
        if (!result.summary().isBlank()) {
            reply.append(result.summary());
        } else {
            reply.append("I've updated your plan according to your request.");
        }
        if (!result.isEmpty() && preview != null) {
            pendingFocusDraft = preview;
            reply.append("\n\n## Updated preview\n\n")
                    .append(focusPlanSummary(preview.plan()))
                    .append("\n\nChoose **Save plan** or **Cancel** below, or request another change.");
        } else if (pendingFocusDraft != null) {
            reply.append("\n\nThe current preview is unchanged. Choose **Save plan** or **Cancel** ")
                    .append("below, or clarify the change.");
        }

        MessageBubble bubble = addMessage("assistant", reply.toString().strip(), List.of());
        if (pendingFocusDraft != null) {
            showDraftActions(bubble);
        }
        finishFocusResponse(false);
    }

    static boolean isPlanHorizonIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = FocusFormats.normalize(text);
        return normalized.contains("keo dai")
                || normalized.contains("rut ngan")
                || normalized.contains("thoi han")
                || normalized.contains("extend")
                || normalized.contains("shorten")
                || normalized.contains("horizon")
                || (normalized.contains("ke hoach") && (normalized.contains("ngay") || normalized.contains("tuan") || normalized.contains("thang")))
                || (normalized.contains("plan") && (normalized.contains("day") || normalized.contains("week") || normalized.contains("month")));
    }

    static List<String> extractPrioritySubjects(String message, List<String> availableSubjects) {
        if (message == null || message.isBlank() || availableSubjects == null || availableSubjects.isEmpty()) {
            return List.of();
        }
        String normalized = FocusFormats.normalize(message);
        boolean hasPriorityKeyword = normalized.contains("uu tien")
                || normalized.contains("don thoi gian")
                || normalized.contains("tap trung")
                || normalized.contains("danh nhieu thoi gian")
                || normalized.contains("priorit")
                || normalized.contains("more time")
                || normalized.contains("focus on");
        if (!hasPriorityKeyword) {
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        for (String subject : availableSubjects) {
            String normSubject = FocusFormats.normalize(subject);
            if (!normSubject.isBlank() && normalized.contains(normSubject)) {
                matched.add(subject);
            }
        }
        return matched;
    }

    static boolean isPlanPriorityIntent(String text, List<String> availableSubjects) {
        return !extractPrioritySubjects(text, availableSubjects).isEmpty();
    }

    static List<String> extractDroppedSubjects(String message, List<String> availableSubjects) {
        if (message == null || message.isBlank() || availableSubjects == null || availableSubjects.isEmpty()) {
            return List.of();
        }
        String normalized = FocusFormats.normalize(message);
        boolean hasDropKeyword = normalized.contains("bo mon")
                || normalized.contains("xoa mon")
                || normalized.contains("khong hoc mon")
                || normalized.contains("loai bo mon")
                || normalized.contains("drop subject")
                || normalized.contains("remove subject")
                || normalized.contains("exclude");
        if (!hasDropKeyword) {
            for (String subject : availableSubjects) {
                String normSubject = FocusFormats.normalize(subject);
                if (normalized.matches(".*\\b(?:bo|xoa|drop|remove)\\s+" + java.util.regex.Pattern.quote(normSubject) + "\\b.*")) {
                    return List.of(subject);
                }
            }
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        for (String subject : availableSubjects) {
            String normSubject = FocusFormats.normalize(subject);
            if (!normSubject.isBlank() && normalized.contains(normSubject)) {
                matched.add(subject);
            }
        }
        return matched;
    }

    static boolean isPlanDropSubjectIntent(String text, List<String> availableSubjects) {
        return !extractDroppedSubjects(text, availableSubjects).isEmpty();
    }

    private boolean handleDropSubject(String message) {
        List<String> dropped = extractDroppedSubjects(message, focusService.subjects());
        if (dropped.isEmpty()) {
            return false;
        }
        for (String subject : dropped) {
            focusService.dropSubject(subject);
        }
        FocusPlan newPlan = focusService.currentPlan();
        StringBuilder reply = new StringBuilder();
        reply.append("Đã loại bỏ môn **")
                .append(String.join(", ", dropped))
                .append("** khỏi kế hoạch học tập. Các buổi học còn lại đã được sắp xếp lại tối ưu.\n\n");
        reply.append(focusPlanSummary(newPlan));
        addMessage("assistant", reply.toString().strip(), List.of());
        Platform.runLater(focusOpenAction);
        mascotStateService.setState(MascotState.TALKING);
        talkingTransition.playFromStart();
        messageInput.requestFocus();
        return true;
    }

    private boolean adjustPlanHorizonOrPriority(String message) {
        List<String> subjectsToPrioritize = extractPrioritySubjects(message, focusService.subjects());
        for (String subject : subjectsToPrioritize) {
            focusService.prioritizeSubject(subject);
        }

        LocalDate today = LocalDate.now();
        Optional<FocusFormats.PlanDateRange> dateRange = FocusFormats.parsePlanDateRange(message, today);
        LocalDate newFrom = null;
        LocalDate newEnd = null;

        if (dateRange.isPresent()) {
            newFrom = dateRange.get().from();
            newEnd = dateRange.get().until();
        } else {
            Optional<LocalDate> targetEnd = FocusFormats.parsePlanEnd(message, today);
            if (targetEnd.isEmpty()) {
                String normalized = FocusFormats.normalize(message);
                java.util.regex.Matcher relativeDays = java.util.regex.Pattern.compile(
                        ".*\\b(?:them|add|extend)\\s+(\\d{1,3})\\s*(?:ngay|days?)\\b.*").matcher(normalized);
                if (relativeDays.matches()) {
                    long daysToAdd = Long.parseLong(relativeDays.group(1));
                    LocalDate latest = focusService.sessions().stream()
                            .map(FocusSession::date)
                            .max(LocalDate::compareTo)
                            .orElse(today);
                    targetEnd = Optional.of(latest.plusDays(daysToAdd));
                }
            }
            newEnd = targetEnd.orElse(null);
        }

        if (newFrom != null || newEnd != null) {
            FocusPreferences updatedPref = focusService.preferences();
            if (newFrom != null && newEnd != null) {
                updatedPref = updatedPref.withPlanScope(newFrom, newEnd);
            } else if (newEnd != null) {
                updatedPref = updatedPref.withPlanUntil(newEnd);
            } else {
                updatedPref = updatedPref.withPlanFrom(newFrom);
            }
            focusService.setPreferences(updatedPref);
        }

        if (newFrom != null || newEnd != null || !subjectsToPrioritize.isEmpty()) {
            FocusPlan newPlan = focusService.generatePlan();

            StringBuilder reply = new StringBuilder();
            if (newFrom != null && newEnd != null && !subjectsToPrioritize.isEmpty()) {
                reply.append("Đã điều chỉnh kế hoạch học tập: từ ngày **")
                        .append(FocusFormats.formatDate(newFrom))
                        .append("** đến ngày **")
                        .append(FocusFormats.formatDate(newEnd))
                        .append("** và ưu tiên môn **")
                        .append(String.join(", ", subjectsToPrioritize))
                        .append("**.\n\n");
            } else if (newFrom != null && newEnd != null) {
                reply.append("Đã điều chỉnh kế hoạch học tập từ ngày **")
                        .append(FocusFormats.formatDate(newFrom))
                        .append("** đến ngày **")
                        .append(FocusFormats.formatDate(newEnd))
                        .append("**.\n\n");
            } else if (newEnd != null && !subjectsToPrioritize.isEmpty()) {
                reply.append("Đã điều chỉnh kế hoạch học tập: kết thúc vào ngày **")
                        .append(FocusFormats.formatDate(newEnd))
                        .append("** và ưu tiên môn **")
                        .append(String.join(", ", subjectsToPrioritize))
                        .append("**.\n\n");
            } else if (newEnd != null) {
                reply.append("Đã điều chỉnh kế hoạch học tập kết thúc vào ngày **")
                        .append(FocusFormats.formatDate(newEnd))
                        .append("**.\n\n");
            } else {
                reply.append("Đã ưu tiên môn **")
                        .append(String.join(", ", subjectsToPrioritize))
                        .append("** trong kế hoạch học tập.\n\n");
            }
            reply.append(focusPlanSummary(newPlan));

            addMessage("assistant", reply.toString().strip(), List.of());
            Platform.runLater(focusOpenAction);
            mascotStateService.setState(MascotState.TALKING);
            talkingTransition.playFromStart();
            messageInput.requestFocus();
            return true;
        }
        return false;
    }

    private boolean adjustPlanHorizon(String message) {
        return adjustPlanHorizonOrPriority(message);
    }

    static boolean isPlanEditIntent(String text, List<FocusSession> sessions) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = FocusFormats.normalize(text);
        boolean hasEditKeyword = normalized.matches(".*\\b(doi|chuyen|chinh|sua|xoa|bo|huy|them|lui|day|hoan|dat lai)\\b.*")
                || normalized.matches(".*\\b(edit|change|reschedule|move|shift|update|modify|delete|remove|cancel|add|adjust)\\b.*")
                || normalized.contains("doi gio")
                || normalized.contains("doi lich")
                || normalized.contains("chinh sua")
                || normalized.contains("thay doi");
        if (hasEditKeyword) {
            return true;
        }
        boolean mentionsSubject = sessions != null && sessions.stream()
                .anyMatch(s -> normalized.contains(FocusFormats.normalize(s.subject())));
        boolean mentionsTime = normalized.matches(".*\\b(\\d{1,2}\\s*(?:h|:|gio|am|pm))\\b.*");
        return mentionsSubject && mentionsTime;
    }

    static String focusPlanSummary(FocusPlan plan) {
        StringBuilder summary = new StringBuilder("**Your plan is ready**\n\n");
        if (plan.sessions().isEmpty()) {
            summary.append("I couldn't place any sessions. Check the notes below, then adjust ")
                    .append("your classes, deadlines or free time in **Study**.");
        } else {
            summary.append(plan.sessions().size()).append(" sessions - ")
                    .append(formatMinutes(plan.totalMinutes())).append(" planned for ")
                    .append(plan.subjectCount()).append(" subjects.\n\n");
            plan.sessions().forEach(session ->
                    summary.append("- **").append(FocusFormats.formatDate(session.date()))
                            .append(" ").append(session.timeRange()).append("** - ")
                            .append(session.subject()).append(": ")
                            .append(session.title()).append('\n'));
            summary.append("\nReview every session above before saving. After saving, use ")
                    .append("**Open Study** to start sessions manually.");
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

    private void streamReply(int generation, List<ChatMessage> conversation,
                             List<DocumentAttachment> documents, long startedAtNanos) {
        try {
            FocusPlan plan = focusService != null ? focusService.currentPlan() : null;
            String reply = chatService.reply(conversation, documents, plan,
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
        if (generation != responseGeneration || token == null || token.isEmpty()) {
            return;
        }
        synchronized (pendingTokens) {
            pendingTokens.append(token);
        }
    }

    /** Coalesces streamed tokens on a fixed UI cadence instead of per-token repaints. */
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
        clearPendingTokens();
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
        clearPendingTokens();
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
        if (wasResponding && streamingMessageIndex >= 0 && streamingMessageIndex < currentMessages.size()) {
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
        modeChoice.setDisable(busy);
    }

    private List<ChatMessage> conversationForModel() {
        List<ChatMessage> conversation = new ArrayList<>();
        for (Message message : currentMessages) {
            if (message.text() == null || message.text().isBlank()
                    || message.status() == StoredMessage.Status.ERROR) {
                continue;
            }
            MessageRole role = "user".equals(message.role())
                    ? MessageRole.USER
                    : MessageRole.ASSISTANT;
            conversation.add(new ChatMessage(role, message.text()));
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
        pendingFocus = null;
        pendingFocusDraft = null;
        clearDraftActions();
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
        pendingFocus = null;
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
        updateSelectedConversation();
        refreshHistoryIfSelected();
        MessageBubble bubble = addMessageNode(message);
        scrollToBottom();
        return bubble;
    }

    private void updateSelectedConversation() {
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
        history.set(selectedHistoryIndex,
                new Conversation(existing.id(), existing.title(), System.currentTimeMillis(), List.copyOf(currentMessages),
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
                                message.elapsedMillis(), message.status())).toList(), chat.attachments()));
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
                        message.elapsedMillis(), message.status(), "TEXT")).toList(), chat.attachments());
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
                    Platform.runLater(() -> showSaveStatus(
                            "Chat title could not be generated. The provisional title is retained.", false));
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
        saveStatus.setText(message);
        saveStatus.setVisible(true);
        saveStatus.setManaged(true);
        if (retry) {
            retrySaveButton.setVisible(true);
            retrySaveButton.setManaged(true);
        }
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
                    && message.status() == StoredMessage.Status.COMPLETE) {
                continue;
            }
            addMessageNode(message);
        }
        scrollToBottom();
    }

    private MessageBubble addMessageNode(Message message) {
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
                           long elapsedMillis, StoredMessage.Status status) {

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

        private Message withText(String text, long elapsedMillis, StoredMessage.Status status) {
            return new Message(id, role, text, attachments, elapsedMillis, status);
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
