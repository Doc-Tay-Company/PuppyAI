package com.puppy.controller;

import com.puppy.config.ServiceRegistry;
import com.puppy.enums.MascotState;
import com.puppy.service.MascotStateService;
import com.puppy.ui.MascotAnimator;
import com.puppy.ui.MascotMessages;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Coordinates the mascot shell, its navigation and the cached feature panels.
 */
public class MascotController {

    private static final double COMPACT_WIDTH = 336;
    private static final double COMPACT_HEIGHT = 252;
    private static final double MENU_WIDTH = 380;
    private static final double MENU_HEIGHT = 360;
    private static final double MENU_BAR_HEIGHT = 76;
    private static final double STUDY_WIDTH = 380;
    private static final double STUDY_HEIGHT = 470;
    private static final double STUDY_MIN_HEIGHT = 340;
    private static final double SETTINGS_WIDTH = 620;
    private static final double SETTINGS_HEIGHT = 780;
    private static final double SETTINGS_MIN_HEIGHT = 420;
    private static final double CHAT_PREFERRED_WIDTH = 940;
    private static final double CHAT_PREFERRED_HEIGHT = 720;
    private static final double FOCUS_PREFERRED_WIDTH = 980;
    private static final double FOCUS_PREFERRED_HEIGHT = 840;
    private static final double SCREEN_EDGE_MARGIN = 48;
    private static final double DRAG_THRESHOLD = 5;
    private static final Duration SLEEP_AFTER = Duration.minutes(5);
    private static final Duration NOTIFICATION_INTERVAL = Duration.minutes(2);
    private static final Duration BUBBLE_DURATION = Duration.seconds(8);
    private static final Duration SINGLE_CLICK_DELAY = Duration.millis(230);

    @FXML
    private StackPane shellRoot;

    @FXML
    private StackPane menuHost;

    @FXML
    private HBox mainMenu;

    @FXML
    private StackPane mascotDock;

    @FXML
    private ImageView mascotView;

    @FXML
    private Label mascotBubble;

    private final Map<Panel, Parent> cachedPanels = new EnumMap<>(Panel.class);
    private final Map<Panel, Stage> panelStages = new EnumMap<>(Panel.class);
    private final Random random = new Random();
    private MascotStateService mascotStateService;
    private Consumer<MascotState> stateListener;
    private MascotAnimator animator;
    private ContextMenu contextMenu;
    private Stage stage;
    private Stage menuStage;
    private ServiceRegistry services;
    private double pressScreenX;
    private double pressScreenY;
    private double pressStageX;
    private double pressStageY;
    private double chatWidth = CHAT_PREFERRED_WIDTH;
    private double chatHeight = CHAT_PREFERRED_HEIGHT;
    private double focusWidth = FOCUS_PREFERRED_WIDTH;
    private double focusHeight = FOCUS_PREFERRED_HEIGHT;
    private boolean draggedSincePress;
    private boolean chatExpanded;
    private boolean closed;
    private long ignoreClicksUntil;
    private Panel activePanel;
    private ChatController chatController;
    private StudyController studyController;
    private FocusController focusController;
    private SettingsController settingsController;
    private PauseTransition singleClickDelay;
    private PauseTransition sleepTimer;
    private PauseTransition notificationTimer;
    private PauseTransition bubbleTimer;
    private PauseTransition reactionTimer;

    @FXML
    public void initialize() {
        animator = new MascotAnimator(mascotView);
        mascotDock.setCursor(Cursor.HAND);
        mascotDock.setOnMousePressed(this::onMousePressed);
        mascotDock.setOnMouseDragged(this::onMouseDragged);
        mascotDock.setOnMouseClicked(this::onMascotClicked);

        singleClickDelay = new PauseTransition(SINGLE_CLICK_DELAY);
        singleClickDelay.setOnFinished(event -> onMascotSingleClick());

        bubbleTimer = new PauseTransition(BUBBLE_DURATION);
        bubbleTimer.setOnFinished(event -> hideBubble());

        reactionTimer = new PauseTransition(Duration.millis(900));
        reactionTimer.setOnFinished(event -> restoreMascotState());

        sleepTimer = new PauseTransition(SLEEP_AFTER);
        sleepTimer.setOnFinished(event -> enterSleepIfEligible());

        notificationTimer = new PauseTransition(NOTIFICATION_INTERVAL);
        notificationTimer.setOnFinished(event -> showNotificationThenReschedule());

        // Hover is presentation only: the mascot now reacts with a hand cursor
        // and stays quiet until it is clicked.
        shellRoot.addEventFilter(MouseEvent.MOUSE_CLICKED, this::dismissMenuOnOutsideClick);
        shellRoot.addEventFilter(MouseEvent.MOUSE_MOVED, event -> noteActivity());
        shellRoot.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> noteActivity());
        shellRoot.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> noteActivity());
        shellRoot.addEventFilter(KeyEvent.KEY_PRESSED, event -> noteActivity());
    }

    /**
     * Connects the shell to application services and the frameless window.
     * Must be called on the JavaFX Application Thread.
     */
    public void attach(Stage stage, ServiceRegistry services) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.services = Objects.requireNonNull(services, "services");
        this.mascotStateService = services.mascotStateService();
        this.stateListener = this::onMascotStateChanged;
        mascotStateService.addListener(stateListener);
        animator.play(mascotStateService.getState());
        stage.setWidth(COMPACT_WIDTH);
        stage.setHeight(COMPACT_HEIGHT);
        shellRoot.getChildren().remove(menuHost);
        menuStage = createAuxiliaryStage(menuHost, "Menu", true);
        menuStage.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if (!closed && wasFocused && !focused) {
                Platform.runLater(() -> {
                    if (!closed && menuStage.isShowing() && !menuStage.isFocused()) {
                        closeMenu();
                    }
                });
            }
        });
        menuStage.getScene().setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                closeMenu();
                event.consume();
            }
        });
        stage.getScene().setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                if (activePanel != null) {
                    collapseToMascot();
                } else {
                    closeMenu();
                }
                event.consume();
            }
        });
        shellRoot.setOnContextMenuRequested(event -> {
            contextMenu().show(shellRoot, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        noteActivity();
        notificationTimer.playFromStart();
    }

    @FXML
    private void openStudy() {
        openPanel(Panel.STUDY);
    }

    @FXML
    private void openFocus() {
        openPanel(Panel.FOCUS);
    }

    @FXML
    private void openChat() {
        openPanel(Panel.CHAT);
    }

    @FXML
    private void openMore() {
        noteActivity();
        hideBubble();
        if (menuHost.isVisible() && activePanel == null && menuHost.getChildren().size() > 0
                && menuHost.getChildren().get(0) != mainMenu) {
            showMainMenu();
            return;
        }
        activePanel = null;
        menuHost.getChildren().setAll(createMoreMenu());
        showMenu(MENU_WIDTH, MENU_HEIGHT);
    }

    private void openPanel(Panel panel) {
        noteActivity();
        hideBubble();
        closeMenu();
        try {
            Parent view = cachedPanels.computeIfAbsent(panel, this::loadPanel);
            if (activePanel != null && activePanel != panel) {
                hidePanelStage(activePanel);
            }
            activePanel = panel;
            Stage panelStage = panelStages.computeIfAbsent(panel, ignored -> createPanelStage(panel, view));
            positionPanelStage(panel, panelStage, false);
            panelStage.show();
            panelStage.toFront();
        } catch (PanelLoadException e) {
            throw new IllegalStateException("Could not open " + panel.name().toLowerCase() + " panel", e.getCause());
        }
    }

    /** Reopens the main Study / Focus / Chat / More menu from any panel. */
    public void returnToMainMenu() {
        noteActivity();
        hideBubble();
        resetChatExpansion();
        showMainMenu();
    }

    private Parent loadPanel(Panel panel) {
        try {
            return switch (panel) {
                case CHAT -> loadChatPanel();
                case STUDY -> loadStudyPanel();
                case FOCUS -> loadFocusPanel();
                case SETTINGS -> loadSettingsPanel();
            };
        } catch (IOException e) {
            throw new PanelLoadException(e);
        }
    }

    private Parent loadChatPanel() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/chat.fxml"));
        Parent view = loader.load();
        chatController = loader.getController();
        chatController.attach(services.chatService(), services.threadManager().executor(),
                mascotStateService, services.focusService(), this::closePanel, this::collapseToMascot,
                this::returnToMainMenu, this::toggleChatExpansion, this::openFocusPanel);
        return view;
    }

    /** Opens Focus after the chat focus option built a plan. */
    private void openFocusPanel() {
        openPanel(Panel.FOCUS);
        if (focusController != null) {
            focusController.refresh();
        }
    }

    private Parent loadFocusPanel() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/focus.fxml"));
        Parent view = loader.load();
        focusController = loader.getController();
        focusController.attach(services.focusService(), mascotStateService,
                services.threadManager().executor(), this::closePanel, this::collapseToMascot,
                this::returnToMainMenu);
        return view;
    }

    private Parent loadStudyPanel() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/study.fxml"));
        Parent view = loader.load();
        studyController = loader.getController();
        studyController.attach(mascotStateService, this::closePanel, this::collapseToMascot,
                this::returnToMainMenu);
        return view;
    }

    private Parent loadSettingsPanel() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/settings.fxml"));
        Parent view = loader.load();
        settingsController = loader.getController();
        settingsController.attach(services.config().appVersion(),
                services.modelService(),
                stage.isAlwaysOnTop(),
                this::setAlwaysOnTop, this::closePanel, this::collapseToMascot,
                this::returnToMainMenu, this::resizeSettingsPanel);
        return view;
    }

    private VBox createMoreMenu() {
        VBox card = new VBox(7);
        card.getStyleClass().add("more-menu");
        card.setAlignment(Pos.CENTER);
        card.getChildren().add(new Label("More"));
        card.getChildren().add(menuItem("Focus planner", () -> openPanel(Panel.FOCUS)));
        card.getChildren().add(menuItem("Settings", () -> openPanel(Panel.SETTINGS)));
        card.getChildren().add(menuItem("Chat history", () -> {
            openPanel(Panel.CHAT);
            chatController.showHistory();
        }));
        card.getChildren().add(menuItem("Help / About", this::showAbout));
        card.getChildren().add(menuItem("Collapse to mascot", this::collapseToMascot));
        card.getChildren().add(menuItem("Back to menu", this::showMainMenu));
        card.getChildren().add(menuItem("Quit Puppy", this::quit));
        return card;
    }

    private Button menuItem(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("menu-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void showAbout() {
        VBox about = new VBox(10);
        about.getStyleClass().add("more-menu");
        about.setAlignment(Pos.CENTER);
        about.getChildren().add(new Label("Puppy AI"));
        about.getChildren().add(new Label("A little desktop companion for chat and focused study."));
        about.getChildren().add(new Label(
                "Chat runs through OpenRouter or the local Ollama model."));
        about.getChildren().add(menuItem("Back to menu", this::showMainMenu));
        menuHost.getChildren().setAll(about);
        showMenu(MENU_WIDTH, MENU_HEIGHT);
    }

    private void showMainMenu() {
        noteActivity();
        hideBubble();
        resetChatExpansion();
        hideActivePanelStage();
        activePanel = null;
        menuHost.getChildren().setAll(mainMenu);
        showMenu(MENU_WIDTH, MENU_BAR_HEIGHT);
    }

    private void closePanel() {
        collapseToMascot();
    }

    private void collapseToMascot() {
        noteActivity();
        hideBubble();
        resetChatExpansion();
        hideActivePanelStage();
        activePanel = null;
        closeMenu();
        restoreMascotState();
    }

    private void closeMenu() {
        noteActivity();
        hideBubble();
        menuHost.setVisible(false);
        menuHost.setManaged(false);
        if (menuStage != null) {
            menuStage.hide();
        }
    }

    private void showMenu(double width, double height) {
        Rectangle2D bounds = primaryScreenBounds();
        width = Math.min(width, Math.max(1, bounds.getWidth() - SCREEN_EDGE_MARGIN * 2));
        height = Math.min(height, Math.max(1, bounds.getHeight() - SCREEN_EDGE_MARGIN * 2));
        menuHost.setPrefSize(width, height);
        menuHost.setManaged(true);
        menuHost.setVisible(true);
        menuStage.setWidth(width);
        menuStage.setHeight(height);
        double x = stage.getX() + stage.getWidth() - width;
        double y = stage.getY() - height - 8;
        menuStage.setX(clamp(x, bounds.getMinX(), Math.max(bounds.getMinX(), bounds.getMaxX() - width)));
        menuStage.setY(clamp(y, bounds.getMinY(), Math.max(bounds.getMinY(), bounds.getMaxY() - height)));
        menuStage.show();
        menuStage.toFront();
    }

    private Stage createAuxiliaryStage(Parent content, String title, boolean owned) {
        Stage auxiliary = new Stage(StageStyle.TRANSPARENT);
        if (owned) {
            auxiliary.initOwner(stage);
        }
        auxiliary.setTitle(services.config().appName() + " - " + title);
        auxiliary.setAlwaysOnTop(owned && stage.isAlwaysOnTop());
        auxiliary.setResizable(false);
        Scene scene = new Scene(content);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().addAll(stage.getScene().getStylesheets());
        auxiliary.setScene(scene);
        return auxiliary;
    }

    private Stage createPanelStage(Panel panel, Parent content) {
        Stage panelStage = createAuxiliaryStage(content, panel.name(), false);
        panelStage.setOnCloseRequest(event -> {
            if (!closed) {
                event.consume();
                hidePanel(panel);
            }
        });
        panelStage.getScene().setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                hidePanel(panel);
                event.consume();
            }
        });
        return panelStage;
    }

    /** Recomputes the Settings window height when the key section appears or hides. */
    private void resizeSettingsPanel() {
        Stage panelStage = panelStages.get(Panel.SETTINGS);
        if (panelStage != null) {
            positionPanelStage(Panel.SETTINGS, panelStage, false);
        }
    }

    private void positionPanelStage(Panel panel, Stage panelStage, boolean expanded) {
        Rectangle2D bounds = primaryScreenBounds();
        double availableWidth = Math.max(1, bounds.getWidth() - SCREEN_EDGE_MARGIN * 2);
        double availableHeight = Math.max(1, bounds.getHeight() - SCREEN_EDGE_MARGIN * 2);
        double preferredWidth;
        double preferredHeight;
        switch (panel) {
            case STUDY -> {
                preferredWidth = STUDY_WIDTH;
                Parent root = panelStage.getScene().getRoot();
                root.applyCss();
                double contentHeight = root.prefHeight(preferredWidth);
                preferredHeight = Double.isFinite(contentHeight) && contentHeight > 0
                        ? Math.min(STUDY_HEIGHT, Math.max(STUDY_MIN_HEIGHT, contentHeight + 24))
                        : STUDY_MIN_HEIGHT;
            }
            case SETTINGS -> {
                preferredWidth = SETTINGS_WIDTH;
                Parent root = panelStage.getScene().getRoot();
                root.applyCss();
                double contentHeight = root.prefHeight(preferredWidth);
                preferredHeight = Double.isFinite(contentHeight) && contentHeight > 0
                        ? Math.min(SETTINGS_HEIGHT, Math.max(SETTINGS_MIN_HEIGHT, contentHeight + 24))
                        : SETTINGS_HEIGHT;
            }
            case FOCUS -> {
                preferredWidth = focusWidth;
                preferredHeight = focusHeight;
            }
            case CHAT -> {
                if (expanded) {
                    preferredWidth = availableWidth;
                    preferredHeight = availableHeight;
                } else {
                    updateChatSize();
                    preferredWidth = chatWidth;
                    preferredHeight = chatHeight;
                }
            }
            default -> throw new IllegalStateException("Unsupported panel: " + panel);
        }

        double width = Math.min(preferredWidth, availableWidth);
        double height = Math.min(preferredHeight, availableHeight);
        panelStage.setWidth(width);
        panelStage.setHeight(height);

        double x = switch (panel) {
            case STUDY -> bounds.getMaxX() - width - SCREEN_EDGE_MARGIN;
            case CHAT, FOCUS, SETTINGS -> bounds.getMinX() + (bounds.getWidth() - width) / 2;
        };
        double y = switch (panel) {
            case STUDY -> bounds.getMinY() + SCREEN_EDGE_MARGIN;
            case CHAT, FOCUS, SETTINGS -> bounds.getMinY() + (bounds.getHeight() - height) / 2;
        };
        panelStage.setX(clamp(x, bounds.getMinX(), Math.max(bounds.getMinX(), bounds.getMaxX() - width)));
        panelStage.setY(clamp(y, bounds.getMinY(), Math.max(bounds.getMinY(), bounds.getMaxY() - height)));
    }

    /**
     * Picks a roomy default chat size for the current monitor while leaving the
     * mascot visible below the panel.
     */
    private void updateChatSize() {
        Rectangle2D bounds = primaryScreenBounds();
        chatWidth = Math.min(CHAT_PREFERRED_WIDTH,
                Math.max(1, bounds.getWidth() - SCREEN_EDGE_MARGIN * 2));
        chatHeight = Math.min(CHAT_PREFERRED_HEIGHT,
                Math.max(1, bounds.getHeight() - SCREEN_EDGE_MARGIN * 2));
    }

    /**
     * Grows the chat panel into the monitor's visual workspace and back.
     *
     * @return {@code true} while the panel is expanded
     */
    private boolean toggleChatExpansion() {
        if (activePanel != Panel.CHAT) {
            return false;
        }
        noteActivity();
        chatExpanded = !chatExpanded;
        positionPanelStage(Panel.CHAT, panelStages.get(Panel.CHAT), chatExpanded);
        return chatExpanded;
    }

    private void hidePanel(Panel panel) {
        hidePanelStage(panel);
        if (activePanel == panel) {
            activePanel = null;
        }
        if (panel == Panel.CHAT) {
            resetChatExpansion();
        }
        restoreMascotState();
    }

    private void hideActivePanelStage() {
        if (activePanel != null) {
            hidePanelStage(activePanel);
        }
    }

    private void hidePanelStage(Panel panel) {
        Stage panelStage = panelStages.get(panel);
        if (panelStage != null) {
            panelStage.hide();
        }
    }

    private void setAlwaysOnTop(boolean alwaysOnTop) {
        stage.setAlwaysOnTop(alwaysOnTop);
        if (menuStage != null) {
            menuStage.setAlwaysOnTop(alwaysOnTop);
        }
    }

    private void resetChatExpansion() {
        chatExpanded = false;
        if (chatController != null) {
            chatController.setExpanded(false);
        }
    }

    private Rectangle2D primaryScreenBounds() {
        return Screen.getPrimary().getVisualBounds();
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void onMousePressed(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        noteActivity();
        pressScreenX = event.getScreenX();
        pressScreenY = event.getScreenY();
        pressStageX = stage.getX();
        pressStageY = stage.getY();
        draggedSincePress = false;
        event.consume();
    }

    private void onMouseDragged(MouseEvent event) {
        if (!event.isPrimaryButtonDown()) {
            return;
        }
        double offsetX = event.getScreenX() - pressScreenX;
        double offsetY = event.getScreenY() - pressScreenY;
        if (!draggedSincePress && Math.hypot(offsetX, offsetY) < DRAG_THRESHOLD) {
            return;
        }
        draggedSincePress = true;
        stage.setX(pressStageX + offsetX);
        stage.setY(pressStageY + offsetY);
        event.consume();
    }

    private void onMascotClicked(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY || draggedSincePress) {
            return;
        }
        if (System.nanoTime() < ignoreClicksUntil) {
            event.consume();
            return;
        }
        noteActivity();
        if (mascotStateService.getState() == MascotState.SLEEPING) {
            wakeUp();
            ignoreClicksUntil = System.nanoTime() + 350_000_000L;
            event.consume();
            return;
        }
        if (event.getClickCount() >= 2) {
            singleClickDelay.stop();
            if (activePanel != null) {
                collapseToMascot();
            } else if (menuHost.isVisible()) {
                closeMenu();
            } else {
                showMainMenu();
            }
            event.consume();
            return;
        }
        // A single click shows a random message, but wait briefly so a
        // double-click still opens the menu without flashing two bubbles.
        singleClickDelay.playFromStart();
        event.consume();
    }

    private void onMascotSingleClick() {
        noteActivity();
        if (activePanel != null || menuHost.isVisible()) {
            return;
        }
        if (mascotStateService.getState() == MascotState.SLEEPING) {
            wakeUp();
            return;
        }
        showBubble(MascotMessages.randomClickMessage(random), false);
    }

    private void wakeUp() {
        singleClickDelay.stop();
        mascotStateService.setState(MascotState.HAPPY);
        noteActivity();
        reactionTimer.playFromStart();
        showBubble(MascotMessages.randomWakeMessage(random), false);
    }

    private void noteActivity() {
        if (closed || sleepTimer == null || mascotStateService == null) {
            return;
        }
        if (mascotStateService.getState() == MascotState.SLEEPING) {
            return;
        }
        sleepTimer.playFromStart();
    }

    private void enterSleepIfEligible() {
        if (activePanel != null || menuHost.isVisible()
                || (studyController != null && studyController.isTimerRunning())
                || (focusController != null && focusController.isTimerRunning())
                || isBusyAnimation()) {
            sleepTimer.playFromStart();
            return;
        }
        hideBubble();
        mascotStateService.setState(MascotState.SLEEPING);
    }

    private boolean isBusyAnimation() {
        MascotState state = mascotStateService.getState();
        return state == MascotState.THINKING
                || state == MascotState.TALKING
                || state == MascotState.STUDYING;
    }

    private void showNotificationThenReschedule() {
        boolean compact = activePanel == null && !menuHost.isVisible()
                && mascotStateService.getState() != MascotState.SLEEPING;
        if (compact && !mascotBubble.isVisible()) {
            showBubble(MascotMessages.randomNotification(random), true);
        }
        notificationTimer.playFromStart();
    }

    private void showBubble(String message, boolean notification) {
        if (activePanel != null || mascotBubble == null) {
            return;
        }
        mascotBubble.getStyleClass().remove("notification-bubble");
        if (notification) {
            mascotBubble.getStyleClass().add("notification-bubble");
        }
        mascotBubble.setText(message);
        mascotBubble.setManaged(true);
        mascotBubble.setVisible(true);
        bubbleTimer.playFromStart();
    }

    private void hideBubble() {
        if (bubbleTimer != null) {
            bubbleTimer.stop();
        }
        if (mascotBubble != null) {
            mascotBubble.setVisible(false);
            mascotBubble.setManaged(false);
        }
    }

    private void restoreMascotState() {
        if (studyController != null && studyController.isTimerRunning()) {
            mascotStateService.setState(MascotState.STUDYING);
        } else if (focusController != null && focusController.isTimerRunning()) {
            mascotStateService.setState(MascotState.STUDYING);
        } else {
            mascotStateService.resetToIdle();
        }
    }

    private void dismissMenuOnOutsideClick(MouseEvent event) {
        if (!menuHost.isVisible()) {
            return;
        }
        Object target = event.getTarget();
        if (target instanceof Node node
                && !isWithin(node, menuHost)
                && !isWithin(node, mascotDock)) {
            closeMenu();
        }
    }

    private static boolean isWithin(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private void onMascotStateChanged(MascotState state) {
        Runnable apply = () -> {
            animator.play(state);
            if (state == MascotState.SLEEPING) {
                hideBubble();
            }
        };
        if (Platform.isFxApplicationThread()) {
            apply.run();
        } else {
            Platform.runLater(apply);
        }
    }

    private ContextMenu contextMenu() {
        if (contextMenu != null) {
            return contextMenu;
        }
        contextMenu = new ContextMenu();
        for (MascotState state : MascotState.values()) {
            MenuItem item = new MenuItem("State: " + state.name().toLowerCase());
            item.setOnAction(event -> {
                noteActivity();
                mascotStateService.setState(state);
            });
            contextMenu.getItems().add(item);
        }
        contextMenu.getItems().add(new SeparatorMenuItem());
        MenuItem quit = new MenuItem("Quit Puppy");
        quit.setOnAction(event -> quit());
        contextMenu.getItems().add(quit);
        return contextMenu;
    }

    private void quit() {
        stage.close();
        Platform.exit();
    }

    /** Stops UI timers and releases the mascot state listener during shutdown. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (studyController != null) {
            studyController.close();
        }
        if (focusController != null) {
            focusController.close();
        }
        if (chatController != null) {
            chatController.close();
        }
        if (sleepTimer != null) {
            sleepTimer.stop();
        }
        if (notificationTimer != null) {
            notificationTimer.stop();
        }
        if (bubbleTimer != null) {
            bubbleTimer.stop();
        }
        if (singleClickDelay != null) {
            singleClickDelay.stop();
        }
        if (reactionTimer != null) {
            reactionTimer.stop();
        }
        if (mascotStateService != null && stateListener != null) {
            mascotStateService.removeListener(stateListener);
        }
        if (animator != null) {
            animator.stop();
        }
        if (menuStage != null) {
            menuStage.close();
        }
        panelStages.values().forEach(Stage::close);
    }

    private enum Panel {
        CHAT,
        STUDY,
        FOCUS,
        SETTINGS
    }

    private static final class PanelLoadException extends RuntimeException {

        private PanelLoadException(IOException cause) {
            super(cause);
        }
    }
}
