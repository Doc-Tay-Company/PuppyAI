package com.puppy.controller;

import com.puppy.ai.AiModel;
import com.puppy.config.AiSettingsStore;
import com.puppy.config.AppConfig;
import com.puppy.config.ServiceRegistry;
import com.puppy.enums.MascotState;
import com.puppy.service.ModelService;
import com.puppy.ui.MarkdownRenderer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxmlLayoutTest {

    @TempDir
    Path tempDirectory;

    @Test
    void allUiLayoutsLoadOnTheJavaFxThread() throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServiceRegistry services = new ServiceRegistry(new AppConfig("Puppy AI", "test", true,
                tempDirectory.resolve("application"), "http://127.0.0.1:1", AppConfig.DEFAULT_OLLAMA_MODEL,
                null, AppConfig.DEFAULT_OPENROUTER_BASE_URL));
        Platform.startup(() -> Platform.runLater(() -> {
            Stage stage = new Stage();
            MascotController controller = null;
            try {
                for (String resource : List.of(
                        "/fxml/shell.fxml",
                        "/fxml/chat.fxml",
                        "/fxml/study.fxml",
                        "/fxml/focus.fxml",
                        "/fxml/settings.fxml")) {
                    new FXMLLoader(getClass().getResource(resource)).load();
                }

                VBox markdown = MarkdownRenderer.render("# Title\n\nHello **bold** and `code`.");
                assertTrue(markdown.getChildren().get(0).getStyleClass().contains("md-heading"),
                        "Markdown headings should render as heading blocks");
                TextFlow paragraph = (TextFlow) markdown.getChildren().get(1);
                assertTrue(paragraph.getChildren().stream()
                                .anyMatch(node -> node.getStyleClass().contains("md-bold")),
                        "Markdown bold text should carry the bold style");
                assertTrue(paragraph.getChildren().stream()
                                .anyMatch(node -> node.getStyleClass().contains("md-inline-code")),
                        "Markdown inline code should render as a code node");

                FXMLLoader shellLoader = new FXMLLoader(getClass().getResource("/fxml/shell.fxml"));
                Parent shell = shellLoader.load();
                controller = shellLoader.getController();
                stage.setScene(new Scene(shell, 336, 252));
                stage.getScene().getStylesheets().add(
                        getClass().getResource("/css/puppy.css").toExternalForm());
                stage.setResizable(false);
                stage.setAlwaysOnTop(true);
                controller.attach(stage, services);
                stage.setX(300);
                stage.setY(300);
                stage.show();

                StackPane mascotDock = (StackPane) shell.lookup(".mascot-dock");
                services.mascotStateService().setState(MascotState.SLEEPING);
                Event.fireEvent(mascotDock, hover(MouseEvent.MOUSE_ENTERED));
                assertEquals(MascotState.SLEEPING, services.mascotStateService().getState(),
                        "Hovering the mascot must not replace its current animation state");
                Event.fireEvent(mascotDock, hover(MouseEvent.MOUSE_EXITED));
                assertEquals(MascotState.SLEEPING, services.mascotStateService().getState(),
                        "Leaving the mascot must not reset its current animation state");
                services.mascotStateService().resetToIdle();
                double initialX = stage.getX();
                double initialY = stage.getY();
                Event.fireEvent(mascotDock, primaryPress(10, 10));
                Event.fireEvent(mascotDock, drag(25, 25));
                assertTrue(Math.abs(stage.getX() - initialX - 15) < 1
                                && Math.abs(stage.getY() - initialY - 15) < 1,
                        "Dragging the mascot should move the window");
                Event.fireEvent(mascotDock, doubleClick());
                assertFalse(isShowingStage(".main-menu"),
                        "Dragging must not trigger the double-click menu");

                double mascotX = stage.getX();
                double mascotY = stage.getY();
                double mascotWidth = stage.getWidth();
                double mascotHeight = stage.getHeight();
                Event.fireEvent(mascotDock, primaryPress(30, 30));
                Event.fireEvent(mascotDock, doubleClick());
                Stage menuStage = findShowingStage(".main-menu");
                assertTrue(menuStage.isShowing(),
                        "Double-click should open the main menu");
                assertEquals(Color.TRANSPARENT, menuStage.getScene().getFill(),
                        "The menu window background should be transparent");
                StackPane menuRoot = (StackPane) menuStage.getScene().getRoot();
                menuRoot.applyCss();
                assertTrue(menuRoot.getBackground() == null
                                || menuRoot.getBackground().getFills().stream()
                                .allMatch(fill -> Color.TRANSPARENT.equals(fill.getFill())),
                        "The area around the menu should be transparent");
                HBox menu = (HBox) menuStage.getScene().lookup(".main-menu");
                menu.applyCss();
                assertTrue(menu.getBackground() != null && menu.getBackground().getFills().stream()
                                .anyMatch(fill -> !Color.TRANSPARENT.equals(fill.getFill())),
                        "The menu itself should keep its visible surface");
                Event.fireEvent(shell, new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                        KeyCode.ESCAPE, false, false, false, false));
                assertFalse(menuStage.isShowing(), "Escape should dismiss the compact main menu");
                Event.fireEvent(mascotDock, doubleClick());
                assertTrue(menuStage.isShowing(), "Double-clicking the mascot should reopen the compact main menu");
                assertPersistentNavigation(shell, mascotDock, menuStage);
                menuStage = findShowingStage(".main-menu");
                menu = (HBox) menuStage.getScene().lookup(".main-menu");

                ((Button) menu.getChildren().get(0)).fire();
                Stage studyStage = findShowingStage(".study-header");
                assertEquals(mascotX, stage.getX(), 1,
                        "Opening Study must not change the mascot's screen X position");
                assertEquals(mascotY, stage.getY(), 1,
                        "Opening Study must not change the mascot's screen Y position");
                assertEquals(mascotWidth, stage.getWidth(), 1,
                        "Opening Study must not resize the mascot window");
                assertEquals(mascotHeight, stage.getHeight(), 1,
                        "Opening Study must not resize the mascot window");
                Rectangle2D screen = Screen.getPrimary().getVisualBounds();
                assertEquals(screen.getMaxX() - studyStage.getWidth() - 48, studyStage.getX(), 1,
                        "Study should be placed at the primary screen's top-right");
                assertEquals(screen.getMinY() + 48, studyStage.getY(), 1,
                        "Study should be placed at the top of the primary screen");
                assertTrue(studyStage.getHeight() >= 340 && studyStage.getHeight() < 470,
                        "Study should fit its content without stretching to the old shell height");
                assertEquals(Color.TRANSPARENT, studyStage.getScene().getFill(),
                        "The Study window background should be transparent");
                studyStage.getScene().getRoot().applyCss();
                VBox studyRoot = (VBox) studyStage.getScene().getRoot();
                studyRoot.layout();
                assertTrue(studyRoot.getBackground() == null
                                || studyRoot.getBackground().getFills().stream()
                                .allMatch(fill -> Color.TRANSPARENT.equals(fill.getFill())),
                        "The Study panel surface should be transparent");
                Node studyContent = studyStage.getScene().lookup(".study-content");
                Bounds studyContentBounds = studyContent.localToScene(studyContent.getBoundsInLocal());
                assertTrue(studyContentBounds.getMaxY() <= studyStage.getScene().getHeight() + 1,
                        "Study content should fit inside the visible window without bottom clipping");
                assertTrue(stage.isAlwaysOnTop(), "The mascot should remain above panel windows");
                assertTrue(studyStage.isAlwaysOnTop(), "Study must honor the global always-on-top preference");
                assertNull(studyStage.getOwner(), "Study should be positioned independently of the mascot stage");
                assertEquals("Study Mode",
                        ((Label) studyStage.getScene().lookup(".study-header .panel-title")).getText(),
                        "The Study panel title should identify Study Mode");
                assertNull(studyStage.getScene().lookup(".study-footer"),
                        "The separate Study footer should be removed");
                Button studyToggle = (Button) studyStage.getScene().lookup("#startPauseButton");                Node panelNode = studyStage.getScene().getRoot();
                Bounds panelBounds = panelNode.localToScene(panelNode.getBoundsInLocal());
                Bounds toggleBounds = studyToggle.localToScene(studyToggle.getBoundsInLocal());
                assertTrue(panelBounds.contains(toggleBounds),
                        "The timer button must stay inside the visible panel to be clickable");
                studyToggle.fire();
                assertTrue("Pause".equals(studyToggle.getText()),
                        "Starting a session should switch the button to Pause");
                assertTrue(services.mascotStateService().getState() == MascotState.STUDYING,
                        "Starting a session should animate the mascot as studying");
                studyToggle.fire();
                assertTrue("Resume".equals(studyToggle.getText()),
                        "Pausing a session should offer Resume");
                assertFocusLossKeepsVisible(studyStage);
                closeActivePanel(studyStage, 0);
                Event.fireEvent(mascotDock, doubleClick());
                assertFalse(studyStage.isShowing(), "Mascot navigation must not force the collapsed panel open");
                ((Button) ((HBox) findShowingStage(".main-menu").getScene().lookup(".main-menu")).getChildren().get(0)).fire();
                assertTrue(studyStage.isShowing());
                assertEquals("Resume", studyToggle.getText(), "Restoring Study must retain its paused timer state");
                Event.fireEvent(mascotDock, doubleClick());
                Event.fireEvent(studyStage.getScene().getRoot(), escape());
                assertTrue(studyStage.isShowing(), "Escape and mascot double-click must not dismiss a feature panel");
                ((Button) ((HBox) findShowingStage(".main-menu").getScene().lookup(".main-menu")).getChildren().get(0)).fire();
                closeActivePanel(studyStage, 1);

                Event.fireEvent(mascotDock, doubleClick());
                menuStage = findShowingStage(".main-menu");
                menu = (HBox) menuStage.getScene().lookup(".main-menu");
                ((Button) menu.getChildren().get(1)).fire();
                Stage focusStage = findShowingStage(".focus-panel");
                assertEquals("Focus Planner",
                        ((Label) focusStage.getScene().lookup(".panel-title")).getText(),
                        "The Focus menu item should open the planner in its own window");
                assertTrue(focusStage.getScene().lookup("#sessionsBox").isVisible(),
                        "The Focus Planner should show its sessions list");
                assertNull(focusStage.getOwner(),
                        "The Focus panel should be positioned independently of the mascot stage");
                TextField focusDraft = (TextField) focusStage.getScene().lookup("#taskSubject");
                focusDraft.setText("Retain this Focus draft");
                closeActivePanel(focusStage, 0);
                Event.fireEvent(mascotDock, doubleClick());
                ((Button) ((HBox) findShowingStage(".main-menu").getScene().lookup(".main-menu")).getChildren().get(1)).fire();
                assertTrue(focusStage.isShowing());
                assertEquals("Retain this Focus draft", focusDraft.getText());
                closeActivePanel(focusStage, 1);

                Event.fireEvent(mascotDock, doubleClick());
                menuStage = findShowingStage(".main-menu");
                menu = (HBox) menuStage.getScene().lookup(".main-menu");
                ((Button) menu.getChildren().get(2)).fire();
                Stage chatStage = findShowingStage(".chat-body");
                assertEquals(Color.TRANSPARENT, chatStage.getScene().getFill(),
                        "The area outside the Chat panel should be transparent");
                assertEquals(mascotX, stage.getX(), 1,
                        "Opening Chat must not change the mascot's screen X position");
                assertEquals(mascotY, stage.getY(), 1,
                        "Opening Chat must not change the mascot's screen Y position");
                assertEquals(screen.getMinX() + (screen.getWidth() - chatStage.getWidth()) / 2,
                        chatStage.getX(), 1, "Chat should be horizontally centered on the primary screen");
                assertEquals(screen.getMinY() + (screen.getHeight() - chatStage.getHeight()) / 2,
                        chatStage.getY(), 1, "Chat should be vertically centered on the primary screen");
                Button expandChat = (Button) chatStage.getScene().lookup("#expandButton");
                expandChat.fire();
                assertEquals("Restore", expandChat.getText());
                assertEquals(screen.getWidth() - 96, chatStage.getWidth(), 1,
                        "Expanded Chat should fill the primary screen with margins");
                expandChat.fire();
                assertEquals("Expand", expandChat.getText());
                assertEquals(mascotWidth, stage.getWidth(), 1,
                        "Resizing Chat must not resize the mascot window");

                VBox messages = (VBox) chatStage.getScene().lookup("#messagesBox");
                TextField messageInput = (TextField) chatStage.getScene().lookup("#messageInput");
                @SuppressWarnings("unchecked")
                ComboBox<ChatController.ComposerMode> modeChoice =
                        (ComboBox<ChatController.ComposerMode>) chatStage.getScene().lookup("#modeChoice");
                assertEquals("Ask me anything", messageInput.getPromptText());
                assertEquals("📌", ((Button) chatStage.getScene().lookup("#attachButton")).getText());
                assertEquals(List.of("Chat", "Focus plan", "Multiple Quiz", "Model", "Help"),
                        modeChoice.getItems().stream().map(Object::toString).toList());
                int messageCount = messages.getChildren().size();
                messageInput.setText("   ");
                ((Button) chatStage.getScene().lookup("#sendButton")).fire();
                assertEquals(messageCount, messages.getChildren().size(),
                        "Blank chat input must not add a message");
                messageInput.clear();
                modeChoice.setValue(ChatController.ComposerMode.MODEL);
                ((Button) chatStage.getScene().lookup("#sendButton")).fire();
                assertEquals(messageCount + 2, messages.getChildren().size(),
                        "The Model option should answer locally without contacting Ollama");
                assertEquals(ChatController.ComposerMode.CHAT, modeChoice.getValue(),
                        "The composer option should return to Chat after running");
                int afterModel = messages.getChildren().size();
                messageInput.setText("Math is important and I need more time");
                modeChoice.setValue(ChatController.ComposerMode.FOCUS);
                ((Button) chatStage.getScene().lookup("#sendButton")).fire();
                assertEquals(afterModel + 2, messages.getChildren().size(),
                        "Focus without a time window should ask when the user is free");
                assertEquals(ChatController.ComposerMode.CHAT, modeChoice.getValue(),
                        "Asking for free time should return the composer to Chat");
                List<Button> messageActions = messages.lookupAll(".message-action").stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .toList();
                assertTrue(messageActions.stream()
                                .anyMatch(button -> "Copy".equals(button.getText())),
                        "User messages should offer a visible Copy action");
                List<Button> reuseButtons = messageActions.stream()
                        .filter(button -> "Reuse".equals(button.getText()))
                        .toList();
                assertFalse(reuseButtons.isEmpty(),
                        "User messages should offer a visible Reuse action");
                reuseButtons.get(reuseButtons.size() - 1).fire();
                assertEquals("Math is important and I need more time", messageInput.getText(),
                        "Reuse should put the previous request back in the message box");
                assertTrue(messages.lookupAll(".message-bubble").stream()
                                .allMatch(node -> node.getOnContextMenuRequested() != null),
                        "Every message should offer a right-click copy menu");
                expandChat.fire();
                closeActivePanel(chatStage, 0);
                Event.fireEvent(mascotDock, doubleClick());
                assertFalse(chatStage.isShowing());
                ((Button) ((HBox) findShowingStage(".main-menu").getScene().lookup(".main-menu")).getChildren().get(2)).fire();
                assertTrue(chatStage.isShowing(), "The Chat menu item must restore the cached window");
                assertEquals("Restore", expandChat.getText(), "Expanded Chat state must survive collapse");
                assertEquals(screen.getWidth() - 96, chatStage.getWidth(), 1);
                assertEquals("Math is important and I need more time", messageInput.getText());
                closeActivePanel(chatStage, 1);

                Event.fireEvent(mascotDock, doubleClick());
                menuStage = findShowingStage(".main-menu");
                menu = (HBox) menuStage.getScene().lookup(".main-menu");
                ((Button) menu.getChildren().get(3)).fire();
                VBox moreMenu = (VBox) menuStage.getScene().lookup(".more-menu");
                ((Button) moreMenu.getChildren().get(2)).fire();
                Stage settingsStage = findShowingStage("#alwaysOnTopCheck");
                CheckBox alwaysOnTop = (CheckBox) settingsStage.getScene().lookup("#alwaysOnTopCheck");
                @SuppressWarnings("unchecked")
                ComboBox<Object> settingsModels =
                        (ComboBox<Object>) settingsStage.getScene().lookup("#modelChoice");
                assertNotNull(settingsModels, "Settings should offer a model picker");
                assertFalse(settingsModels.getItems().isEmpty(),
                        "Settings should list at least the local model");
                assertNotNull(settingsModels.getValue(),
                        "Settings should show the active model");
                assertTrue(settingsStage.getScene().lookup("#openRouterKeyField")
                                instanceof PasswordField,
                        "The OpenRouter key field must be masked");
                assertMoreInstructionsWork(settingsStage, services);
                alwaysOnTop.fire();
                awaitFx(() -> !stage.isAlwaysOnTop());
                assertFalse(stage.isAlwaysOnTop(),
                        "The in-session always-on-top setting should update the mascot stage");
                assertFalse(settingsStage.isAlwaysOnTop(),
                        "Settings must follow the disabled global always-on-top preference");
                assertFalse(studyStage.isAlwaysOnTop());
                assertFalse(focusStage.isAlwaysOnTop());
                assertFalse(chatStage.isAlwaysOnTop());
                assertFalse(menuStage.isAlwaysOnTop());
                closeActivePanel(settingsStage, 0);
                Event.fireEvent(mascotDock, doubleClick());
                ((Button) ((HBox) findShowingStage(".main-menu").getScene().lookup(".main-menu")).getChildren().get(3)).fire();
                ((Button) ((VBox) findShowingStage(".more-menu").getScene().lookup(".more-menu")).getChildren().get(2)).fire();
                assertTrue(settingsStage.isShowing());
                assertEquals("Keep this unsaved draft", ((TextArea) settingsStage.getScene()
                        .lookup("#instructionsInput")).getText());
                closeActivePanel(settingsStage, 1);

                Event.fireEvent(mascotDock, doubleClick());
                menuStage = findShowingStage(".main-menu");
                ((Button) ((HBox) menuStage.getScene().lookup(".main-menu")).getChildren().get(3)).fire();
                ((Button) ((VBox) menuStage.getScene().lookup(".more-menu")).getChildren().get(2)).fire();
                settingsStage = findShowingStage("#alwaysOnTopCheck");
                assertEquals("Keep this unsaved draft", ((TextArea) settingsStage.getScene()
                        .lookup("#instructionsInput")).getText(), "Reopening Settings must retain the draft");
                assertEquals("", services.appSettings().additionalInstructions(), "A retained draft must not be applied");
                closeActivePanel(settingsStage, 1);

                // The key section follows the selected model. Use an isolated
                // data directory so the user's saved model is never touched.
                FXMLLoader isolatedLoader =
                        new FXMLLoader(getClass().getResource("/fxml/settings.fxml"));
                Parent isolatedSettings = isolatedLoader.load();
                SettingsController isolatedController = isolatedLoader.getController();
                ModelService isolatedModels = new ModelService(
                        new AppConfig("Puppy AI", "test", true, tempDirectory,
                                "http://localhost:11434", AppConfig.DEFAULT_OLLAMA_MODEL,
                                null, AppConfig.DEFAULT_OPENROUTER_BASE_URL),
                        new AiSettingsStore(tempDirectory));
                isolatedController.attach("test", isolatedModels, true,
                        value -> { }, () -> { }, () -> { }, () -> { }, () -> { });
                new Scene(isolatedSettings);
                isolatedSettings.applyCss();
                VBox keySection = (VBox) isolatedSettings.lookup("#openRouterKeySection");
                assertFalse(keySection.isVisible(),
                        "The OpenRouter key section should be hidden for the local model");
                assertFalse(keySection.isManaged(),
                        "A hidden key section must not reserve space in the layout");
                @SuppressWarnings("unchecked")
                ComboBox<AiModel> isolatedChoices =
                        (ComboBox<AiModel>) isolatedSettings.lookup("#modelChoice");
                assertEquals(4, isolatedChoices.getItems().size(),
                        "Cloud models should stay listed before a key exists");
                assertTrue(isolatedChoices.getItems().stream()
                                .filter(AiModel::cloud)
                                .allMatch(model -> model.toString().startsWith("OpenRouter · ")),
                        "Cloud choices should name OpenRouter in the picker");
                AiModel localChoice = isolatedChoices.getItems().stream()
                        .filter(model -> !model.cloud())
                        .findFirst()
                        .orElseThrow();
                assertTrue(localChoice.toString().endsWith(" · local"),
                        "The local choice should keep its local label");
                AiModel cloudChoice = isolatedChoices.getItems().stream()
                        .filter(AiModel::cloud)
                        .findFirst()
                        .orElseThrow();
                isolatedChoices.setValue(cloudChoice);
                assertNotNull(isolatedChoices.getOnAction(),
                        "Changing the model should run the Settings handler");
                isolatedChoices.getOnAction().handle(new ActionEvent());
                assertTrue(keySection.isVisible(),
                        "Selecting an OpenRouter model should reveal the key section");
                assertTrue(keySection.isManaged(),
                        "The revealed key section must take part in the layout");
                assertFalse(((PasswordField) isolatedSettings.lookup("#openRouterKeyField"))
                                .isDisabled(),
                        "The key field should be editable when no key is saved");
                assertEquals("Not configured",
                        ((Label) isolatedSettings.lookup("#openRouterKeyPill")).getText());
                isolatedChoices.setValue(localChoice);
                isolatedChoices.getOnAction().handle(new ActionEvent());
                assertFalse(keySection.isVisible(),
                        "Returning to the local model should hide the key section again");

                Event.fireEvent(mascotDock, doubleClick());
                menuStage = findShowingStage(".main-menu");
                menu = (HBox) menuStage.getScene().lookup(".main-menu");
                ((Button) menu.getChildren().get(3)).fire();
                moreMenu = (VBox) menuStage.getScene().lookup(".more-menu");
                ((Button) moreMenu.getChildren().get(3)).fire();
                chatStage = findShowingStage("#historyPane");
                assertTrue(chatStage.getScene().lookup("#historyPane").isVisible(),
                        "More should open session chat history");
                assertHistoryChangesOrderOnlyForNewMessages(chatStage, services);
                var savedHistory = services.chatHistory().flush();
                awaitFx(savedHistory::isDone);
                savedHistory.join();
                FXMLLoader restoredLoader = new FXMLLoader(getClass().getResource("/fxml/chat.fxml"));
                restoredLoader.load();
                ChatController restoredController = restoredLoader.getController();
                restoredController.attach(services.chatService(), services.threadManager().executor(),
                        services.mascotStateService(), services.focusService(), () -> { }, () -> { },
                        () -> { }, () -> false, () -> { });
                restoredController.attachHistory(services.chatHistory());
                assertFalse(((VBox) restoredLoader.getNamespace().get("messagesBox")).getChildren().isEmpty(),
                        "Restored active history must reconstruct visible messages");
                restoredController.close();
                closeActivePanel(chatStage, 1);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                if (controller != null) {
                    controller.close();
                }
                stage.close();
                loaded.countDown();
            }
        }));

        assertTrue(loaded.await(15, TimeUnit.SECONDS), "JavaFX did not load the FXML layouts in time");
        services.chatHistory().flush().join();
        var expectedChats = services.chatHistory().conversations();
        String expectedActive = services.chatHistory().activeId();
        services.close();
        try (ServiceRegistry reopened = new ServiceRegistry(services.config())) {
            assertEquals(expectedChats, reopened.chatHistory().conversations(),
                    "The actual UI-created chat snapshots must survive a database reopen");
            assertEquals(expectedActive, reopened.chatHistory().activeId());
        }
        if (failure.get() != null) {
            throw new AssertionError("An FXML layout could not be loaded", failure.get());
        }
        Platform.exit();
    }

    private static KeyEvent escape() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false);
    }

    private static void assertFocusLossKeepsVisible(Stage target) {
        assertFocusLoss(target, true);
    }

    private static void assertFocusLoss(Stage target, boolean staysVisible) {
        Stage other = new Stage();
        try {
            target.toFront();
            target.requestFocus();
            awaitFx(target::isFocused);
            other.setScene(new Scene(new StackPane(new Label("Another application")), 160, 100));
            other.setAlwaysOnTop(true);
            other.show();
            other.toFront();
            other.requestFocus();
            awaitFx(() -> staysVisible ? other.isFocused() : !target.isShowing());
            var pending = new java.util.concurrent.CompletableFuture<Void>();
            Platform.runLater(() -> pending.complete(null));
            awaitFx(pending::isDone);
            assertFalse(target.isFocused());
            assertEquals(staysVisible, target.isShowing(),
                    "Only the compact main menu should auto-dismiss when focus changes");
        } finally {
            other.close();
        }
    }

    private static void assertPersistentNavigation(Parent shell, StackPane mascotDock, Stage menuStage) {
        assertFocusLoss(menuStage, false);
        Event.fireEvent(mascotDock, doubleClick());
        Event.fireEvent(shell, hover(MouseEvent.MOUSE_CLICKED));
        assertFalse(menuStage.isShowing(), "Clicking outside the compact main menu should dismiss it");
        Event.fireEvent(mascotDock, doubleClick());
        Parent menuRoot = menuStage.getScene().getRoot();
        menuRoot.applyCss();
        menuRoot.layout();
        HBox main = (HBox) menuRoot.lookup(".main-menu");
        assertEquals(List.of("Study", "Focus", "Chat", "More"),
                main.getChildren().stream().map(Button.class::cast).map(Button::getText).toList(),
                "The main menu must have only its original four navigation buttons");
        assertTrue(main.lookupAll(".window-button").isEmpty());
        for (Node node : main.getChildren()) {
            Bounds bounds = node.localToScene(node.getBoundsInLocal());
            assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= menuStage.getWidth(),
                    "Navigation buttons must fit inside the compact menu");
        }
        Event.fireEvent(menuRoot, escape());
        assertFalse(menuStage.isShowing());
        Event.fireEvent(mascotDock, doubleClick());
        assertTrue(isShowingStage(".main-menu"));
        Event.fireEvent(mascotDock, doubleClick());
        assertFalse(menuStage.isShowing(), "Mascot double-click should toggle the compact main menu off");
        Event.fireEvent(mascotDock, doubleClick());
        ((Button) ((HBox) menuRoot.lookup(".main-menu")).getChildren().get(3)).fire();
        VBox more = (VBox) menuRoot.lookup(".more-menu");
        assertFocusLossKeepsVisible(menuStage);
        closeActivePanel(menuStage, 0);
        Event.fireEvent(mascotDock, doubleClick());
        assertTrue(isShowingStage(".main-menu"), "Mascot double-click always opens main navigation");
        ((Button) ((HBox) menuRoot.lookup(".main-menu")).getChildren().get(3)).fire();
        more = (VBox) menuRoot.lookup(".more-menu");
        ((Button) more.getChildren().get(4)).fire();
        VBox about = (VBox) menuRoot.lookup(".more-menu");
        Event.fireEvent(menuRoot, escape());
        assertTrue(menuStage.isShowing());
        closeActivePanel(menuStage, 0);
        Event.fireEvent(mascotDock, doubleClick());
        assertTrue(isShowingStage(".main-menu"), "A collapsed submenu must not block access to main navigation");
        ((Button) ((HBox) menuRoot.lookup(".main-menu")).getChildren().get(3)).fire();
        closeActivePanel(menuStage, 1);
        Event.fireEvent(mascotDock, doubleClick());
        assertTrue(isShowingStage(".main-menu"), "Explicit X close should return to main navigation on next open");
        Event.fireEvent(menuStage, new javafx.stage.WindowEvent(menuStage,
                javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
        assertFalse(menuStage.isShowing());
        Event.fireEvent(mascotDock, doubleClick());
        assertTrue(menuStage.isShowing(), "An OS close request must not strand a hidden navigation stage");
    }

    private static void assertMoreInstructionsWork(Stage settingsStage, ServiceRegistry services) {
        Parent root = settingsStage.getScene().getRoot();
        TextArea input = (TextArea) root.lookup("#instructionsInput");
        Button save = (Button) root.lookup("#saveInstructionsButton");
        Button reset = (Button) root.lookup("#resetInstructionsButton");
        Label status = (Label) root.lookup("#instructionsStatus");
        Label counter = (Label) root.lookup("#instructionsCounter");
        VBox body = (VBox) root.lookup("#settingsBody");
        assertEquals("More instructions", ((Label) ((VBox) body.getChildren().get(3)).getChildren().getFirst()).getText());
        assertEquals(5, input.getPrefRowCount());
        assertTrue(input.isWrapText());
        assertTrue(save.isDisabled());
        String saved = "Use simple explanations.\nInclude examples.";
        input.setText(saved);
        assertEquals("Unsaved changes", status.getText());
        assertEquals("", services.appSettings().additionalInstructions());
        save.fire();
        assertEquals("Saving...", status.getText());
        assertTrue(save.isDisabled());
        awaitFx(() -> !input.isDisabled());
        assertEquals("Saved", status.getText());
        assertEquals(saved, services.appSettings().additionalInstructions());
        reset.fire();
        assertEquals("", input.getText());
        assertEquals(saved, services.appSettings().additionalInstructions(), "Reset must only clear the draft");
        assertEquals("Unsaved changes", status.getText());
        save.fire();
        awaitFx(() -> !input.isDisabled());
        assertEquals("", services.appSettings().additionalInstructions());
        input.setText("\uD83D\uDC36".repeat(4_000));
        assertEquals("4,000 / 4,000", counter.getText());
        assertFalse(save.isDisabled());
        input.setText("x".repeat(4_001));
        assertEquals(4_001, input.getText().length(), "Pasted text must not be silently truncated");
        assertTrue(save.isDisabled());
        assertTrue(status.getStyleClass().contains("error-copy"));
        input.setText("Retain this failed-save draft");
        executeSqlOffFx(services, "CREATE TRIGGER fail_instructions BEFORE UPDATE ON settings WHEN NEW.key='additionalInstructions' BEGIN SELECT RAISE(ABORT,'injected'); END");
        save.fire();
        awaitFx(() -> !input.isDisabled());
        assertTrue(status.getStyleClass().contains("error-copy"));
        assertEquals("Retain this failed-save draft", input.getText());
        assertEquals("", services.appSettings().additionalInstructions());
        assertFalse(save.isDisabled(), "Failed saves must remain retryable");
        executeSqlOffFx(services, "DROP TRIGGER fail_instructions");

        @SuppressWarnings("unchecked")
        ComboBox<AiModel> models = (ComboBox<AiModel>) root.lookup("#modelChoice");
        AiModel originalModel = models.getValue();
        models.setValue(models.getItems().stream().filter(AiModel::cloud).findFirst().orElseThrow());
        awaitFx(() -> root.lookup("#openRouterKeySection").isVisible());
        assertTrue(root.lookup("#instructionsSection").isVisible());
        settingsStage.setHeight(480);
        root.applyCss();
        root.layout();
        Node header = root.lookup(".panel-header");
        Bounds headerBefore = header.localToScene(header.getBoundsInLocal());
        ScrollPane scroll = (ScrollPane) root.lookup("#settingsScroll");
        scroll.setVvalue(1);
        root.layout();
        Bounds headerAfter = header.localToScene(header.getBoundsInLocal());
        assertEquals(headerBefore, headerAfter, "The Settings header must stay fixed when content scrolls");
        Node viewport = scroll.lookup(".viewport");
        Bounds viewportBounds = viewport.localToScene(viewport.getBoundsInLocal());
        Node version = root.lookup("#versionLabel");
        Bounds versionBounds = version.localToScene(version.getBoundsInLocal());
        assertTrue(versionBounds.getMaxY() <= viewportBounds.getMaxY() + 1,
                "The bottom Settings content must be reachable at small heights with the cloud section open");
        models.setValue(originalModel);
        awaitFx(() -> !root.lookup("#openRouterKeySection").isVisible());
        input.setText("Keep this unsaved draft");
    }

    private static void executeSqlOffFx(ServiceRegistry services, String sql) {
        var future = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:"
                    + services.config().dataDirectory().resolve("puppy.db"));
                 var statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (java.sql.SQLException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        awaitFx(future::isDone);
        future.join();
    }

    private static void assertHistoryChangesOrderOnlyForNewMessages(Stage chatStage, ServiceRegistry services) {
        VBox items = (VBox) chatStage.getScene().lookup("#historyItems");
        String originalTitle = ((Button) items.getChildren().getFirst()).getText();
        chatStage.getScene().getRoot().lookupAll(".quiet-button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> "New".equals(button.getText())).findFirst().orElseThrow().fire();
        @SuppressWarnings("unchecked")
        ComboBox<ChatController.ComposerMode> mode =
                (ComboBox<ChatController.ComposerMode>) chatStage.getScene().lookup("#modeChoice");
        Button send = (Button) chatStage.getScene().lookup("#sendButton");
        mode.setValue(ChatController.ComposerMode.HELP);
        send.fire();
        List<String> initialOrder = items.getChildren().stream().map(Button.class::cast).map(Button::getText).toList();
        assertEquals(2, initialOrder.size());
        assertEquals(originalTitle, initialOrder.getLast());
        var originalActivity = services.chatHistory().conversations().stream().collect(
                java.util.stream.Collectors.toMap(com.puppy.model.StoredConversation::id,
                        com.puppy.model.StoredConversation::updatedAt));

        ((Button) items.getChildren().getLast()).fire();
        ((Button) items.getChildren().getFirst()).fire();
        assertEquals(initialOrder, items.getChildren().stream().map(Button.class::cast).map(Button::getText).toList(),
                "Switching to and away from an older conversation must not reorder the sidebar");
        assertEquals(originalActivity, services.chatHistory().conversations().stream().collect(
                java.util.stream.Collectors.toMap(com.puppy.model.StoredConversation::id,
                        com.puppy.model.StoredConversation::updatedAt)),
                "Navigation snapshots must preserve persisted message activity timestamps");

        ((Button) items.getChildren().getLast()).fire();
        mode.setValue(ChatController.ComposerMode.HELP);
        send.fire();
        assertEquals(originalTitle, ((Button) items.getChildren().getFirst()).getText(),
                "A new message in an older conversation must move it to the top");
        var promoted = services.chatHistory().conversations().getFirst();
        assertTrue(promoted.updatedAt() > originalActivity.get(promoted.id()));
        var other = services.chatHistory().conversations().getLast();
        assertEquals(originalActivity.get(other.id()).longValue(), other.updatedAt(),
                "Sending in another conversation must not change this conversation's activity");
    }

    private static void awaitFx(java.util.function.BooleanSupplier ready) {
        Object key = new Object();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        javafx.animation.Timeline poll = new javafx.animation.Timeline();
        poll.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.millis(10), event -> {
            if (ready.getAsBoolean() || System.nanoTime() > deadline) {
                poll.stop();
                Platform.exitNestedEventLoop(key, null);
            }
        }));
        poll.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        poll.play();
        Platform.enterNestedEventLoop(key);
        assertTrue(ready.getAsBoolean(), "Asynchronous UI operation did not finish");
    }

    private static MouseEvent hover(EventType<MouseEvent> type) {
        return new MouseEvent(type,
                10, 10, 10, 10, MouseButton.NONE, 0,
                false, false, false, false,
                false, false, false, false, false, false, null);
    }

    private static MouseEvent doubleClick() {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED,
                10, 10, 10, 10, MouseButton.PRIMARY, 2,
                false, false, false, false,
                false, false, false, false, false, true, null);
    }

    private static MouseEvent primaryPress(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_PRESSED,
                x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false, false, false, true, null);
    }

    private static MouseEvent drag(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_DRAGGED,
                x, y, x, y, MouseButton.NONE, 1,
                false, false, false, false,
                true, false, false, false, false, false, null);
    }

    private static Stage findShowingStage(String selector) {
        return Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(Window::isShowing)
                .filter(window -> window.getScene() != null
                        && window.getScene().lookup(selector) != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No showing stage contains " + selector));
    }

    private static boolean isShowingStage(String selector) {
        return Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .anyMatch(window -> window.isShowing() && window.getScene() != null
                        && window.getScene().lookup(selector) != null);
    }

    private static void closeActivePanel(Stage panelStage, int buttonIndex) {
        List<Button> windowButtons = panelStage.getScene().getRoot().lookupAll(".window-button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .toList();
        windowButtons.stream().filter(button -> button.getText().equals(buttonIndex == 0 ? "_" : "X"))
                .findFirst().orElseThrow().fire();
        assertFalse(panelStage.isShowing(), "Closing a panel should return to the mascot");
    }
}
