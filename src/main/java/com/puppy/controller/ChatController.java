package com.puppy.controller;

import com.puppy.ai.OllamaException;
import com.puppy.document.DocumentExtractionException;
import com.puppy.enums.MascotState;
import com.puppy.enums.MessageRole;
import com.puppy.model.ChatMessage;
import com.puppy.model.DocumentAttachment;
import com.puppy.service.ChatService;
import com.puppy.service.MascotStateService;
import com.puppy.ui.MarkdownRenderer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/**
 * Chat panel backed by the local Ollama model.
 *
 * <p>Replies stream from {@link ChatService} on the shared worker pool. All
 * JavaFX updates are marshalled back with {@link Platform#runLater}. The panel
 * also keeps a small session-only history and reads PDF/Word attachments into
 * the current conversation context.</p>
 */
public class ChatController {

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
    private ImageView chatMascotView;

    private final List<Conversation> history = new ArrayList<>();
    private final List<Message> currentMessages = new ArrayList<>();
    private final List<DocumentAttachment> attachments = new ArrayList<>();

    private ChatService chatService;
    private ExecutorService executor;
    private MascotStateService mascotStateService;
    private Runnable closeAction;
    private Runnable minimizeAction;
    private Runnable menuAction;
    private BooleanSupplier expandAction;

    private boolean responding;
    private boolean extracting;
    private int selectedHistoryIndex = -1;
    private volatile int responseGeneration;
    private int attachmentGeneration;
    private Future<?> inFlight;

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
                       MascotStateService mascotStateService,
                       Runnable closeAction, Runnable minimizeAction,
                       Runnable menuAction, BooleanSupplier expandAction) {
        this.chatService = Objects.requireNonNull(chatService, "chatService");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.mascotStateService = Objects.requireNonNull(mascotStateService, "mascotStateService");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.minimizeAction = Objects.requireNonNull(minimizeAction, "minimizeAction");
        this.menuAction = Objects.requireNonNull(menuAction, "menuAction");
        this.expandAction = Objects.requireNonNull(expandAction, "expandAction");
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

        String raw = messageInput.getText();
        String message = raw == null ? "" : raw.strip();
        if (message.isEmpty() && attachments.isEmpty()) {
            return;
        }
        if (message.isEmpty()) {
            message = ATTACHMENT_ONLY_PROMPT;
        }

        messageInput.clear();
        stopMascotReaction();
        if (handleLocalCommand(message)) {
            messageInput.requestFocus();
            return;
        }

        List<String> fileNames = attachments.stream()
                .map(DocumentAttachment::fileName)
                .toList();

        addMessage("user", message, fileNames);
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

        try {
            inFlight = executor.submit(
                    () -> streamReply(generation, conversation, requestAttachments));
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

    /**
     * Handles slash commands before a request reaches Ollama. The command output
     * is a normal assistant message, so it stays in the session history.
     *
     * @return {@code true} when the input was a command and needs no model reply
     */
    private boolean handleLocalCommand(String input) {
        if (input == null || !input.startsWith("/")) {
            return false;
        }
        String command = input.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String reply = switch (command) {
            case "/model" -> modelCommandReply();
            case "/help" -> """
                    **Puppy commands**

                    - `/model` - show the local model and server
                    - `/help` - show this command list

                    Everything else is sent to the local model.""";
            default -> "I don't know `" + input.strip() + "` yet. Try `/model` or `/help`.";
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

    private String modelCommandReply() {
        if (chatService == null) {
            return "I don't know which model is loaded yet.";
        }
        return "**Model:** `" + chatService.modelName() + "`\n\n"
                + "**Server:** " + chatService.baseUrl() + "\n\n"
                + "I run locally through Ollama. Type `/help` for other commands.";
    }

    private void streamReply(int generation, List<ChatMessage> conversation,
                             List<DocumentAttachment> documents) {
        try {
            String reply = chatService.reply(conversation, documents,
                    token -> enqueueToken(generation, token));
            Platform.runLater(() -> completeReply(generation, reply));
        } catch (OllamaException e) {
            if (e.reason() == OllamaException.Reason.INTERRUPTED) {
                return;
            }
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? "I couldn't get a reply from the local model."
                    : e.getMessage();
            Platform.runLater(() -> failReply(generation, message));
        } catch (RuntimeException e) {
            Platform.runLater(() -> failReply(generation,
                    "Something went wrong while talking to the local model. Please try again."));
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

        if (streamingMessageIndex < 0) {
            streamingBubble = addMessage("assistant", "", List.of());
            streamingMessageIndex = currentMessages.size() - 1;
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
        currentMessages.set(streamingMessageIndex,
                new Message("assistant", text, List.of()));
        messageScroll.setVvalue(1.0);
    }

    private void completeReply(int generation, String reply) {
        if (generation != responseGeneration || !responding) {
            return;
        }
        clearPendingTokens();
        streamTicker.stop();

        String finalText = reply == null ? "" : reply.strip();
        if (finalText.isEmpty()) {
            finalText = "The local model returned an empty reply. Try rephrasing your question.";
        }

        if (streamingMessageIndex >= 0 && streamingMessageIndex < currentMessages.size()) {
            currentMessages.set(streamingMessageIndex,
                    new Message("assistant", finalText, List.of()));
            if (streamingBubble != null) {
                renderBubbleContent(streamingBubble, finalText);
            }
            updateSelectedConversation();
            refreshHistoryIfSelected();
            scrollToBottom();
        } else {
            addMessage("assistant", finalText, List.of());
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
                ? "I couldn't get a reply from the local model."
                : message;

        if (streamingMessageIndex >= 0 && streamingMessageIndex < currentMessages.size()) {
            String partial = streamingText.toString().strip();
            String combined = partial.isEmpty() ? error : partial + "\n\n" + error;
            currentMessages.set(streamingMessageIndex,
                    new Message("assistant", combined, List.of()));
            if (streamingBubble != null) {
                renderBubbleContent(streamingBubble, combined);
            }
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
        boolean busy = responding || extracting;
        sendButton.setDisable(busy);
        attachButton.setDisable(busy);
    }

    private List<ChatMessage> conversationForModel() {
        List<ChatMessage> conversation = new ArrayList<>();
        for (Message message : currentMessages) {
            if (message.text() == null || message.text().isBlank()) {
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
        stopMascotReaction();
        if (selectedHistoryIndex < 0) {
            archiveCurrentConversation();
        }
        selectedHistoryIndex = -1;
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
        if (currentMessages.stream().noneMatch(message -> "user".equals(message.role()))) {
            return;
        }
        String title = currentMessages.stream()
                .filter(message -> "user".equals(message.role()))
                .findFirst()
                .map(Message::text)
                .map(text -> text.strip().replaceAll("\\s+", " "))
                .map(text -> text.length() > 32 ? text.substring(0, 29) + "..." : text)
                .orElse("Conversation");
        history.add(new Conversation(title, List.copyOf(currentMessages),
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
        stopMascotReaction();
        if (selectedHistoryIndex < 0) {
            archiveCurrentConversation();
        } else {
            updateSelectedConversation();
        }
        selectedHistoryIndex = index;
        Conversation conversation = history.get(index);
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
        for (int i = 0; i < history.size(); i++) {
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
        Message message = new Message(role, text, fileNames);
        currentMessages.add(message);
        updateSelectedConversation();
        refreshHistoryIfSelected();
        MessageBubble bubble = addMessageNode(message);
        scrollToBottom();
        return bubble;
    }

    private void updateSelectedConversation() {
        if (selectedHistoryIndex < 0 || selectedHistoryIndex >= history.size()) {
            return;
        }
        Conversation existing = history.get(selectedHistoryIndex);
        history.set(selectedHistoryIndex,
                new Conversation(existing.title(), List.copyOf(currentMessages),
                        List.copyOf(attachments)));
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
            if (message.text().isBlank() && message.attachments().isEmpty()) {
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

        for (String fileName : message.attachments()) {
            Label file = new Label("Attached: " + fileName);
            file.setWrapText(true);
            file.setMaxWidth(BUBBLE_MAX_WIDTH);
            file.getStyleClass().add("message-attachment");
            column.getChildren().add(file);
        }

        HBox row = new HBox(column);
        row.setAlignment(user ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        messagesBox.getChildren().add(row);

        MessageBubble handle = new MessageBubble(bubble, content);
        renderBubbleContent(handle, message.text());
        return handle;
    }

    private void renderBubbleContent(MessageBubble bubble, String text) {
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
                ? "Hi! I'm Puppy. Attach a PDF or Word document and let's read it together."
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

    private record MessageBubble(VBox box, VBox content) {
    }

    private record Message(String role, String text, List<String> attachments) {

        private Message {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(text, "text");
            attachments = List.copyOf(attachments);
        }
    }

    private record Conversation(String title, List<Message> messages,
                                List<DocumentAttachment> attachments) {

        private Conversation {
            messages = List.copyOf(messages);
            attachments = List.copyOf(attachments);
        }
    }
}
