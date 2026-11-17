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
        Platform.startup(() -> Platform.runLater(() -> {
            Stage stage = new Stage();
            ServiceRegistry services = new ServiceRegistry(AppConfig.load());
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
                assertFalse(menuStage.isShowing(),
                        "Escape should dismiss the menu");
                Event.fireEvent(mascotDock, doubleClick());
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
                assertFalse(studyStage.isAlwaysOnTop(), "Study should not be always-on-top of the mascot");
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
                assertEquals(List.of("Chat", "Focus plan", "Model", "Help"),
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
                Label settingsVersion = (Label) settingsStage.getScene().lookup("#versionLabel");
                settingsVersion.getScene().getRoot().applyCss();
                settingsVersion.getScene().getRoot().layout();
                Bounds settingsVersionBounds =
                        settingsVersion.localToScene(settingsVersion.getBoundsInLocal());
                assertTrue(settingsVersionBounds.getMaxY()
                                <= settingsStage.getScene().getHeight() + 1,
                        "Settings content should fit inside the window without clipping");
                alwaysOnTop.fire();
                assertFalse(stage.isAlwaysOnTop(),
                        "The in-session always-on-top setting should update the mascot stage");
                assertFalse(settingsStage.isAlwaysOnTop(),
                        "Panel stages should stay behind the mascot when always-on-top is disabled");
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
                closeActivePanel(chatStage, 1);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                if (controller != null) {
                    controller.close();
                }
                stage.close();
                services.close();
                loaded.countDown();
            }
        }));

        assertTrue(loaded.await(15, TimeUnit.SECONDS), "JavaFX did not load the FXML layouts in time");
        assertNull(failure.get(), "An FXML layout could not be loaded");
        Platform.exit();
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
        windowButtons.get(buttonIndex).fire();
        assertFalse(panelStage.isShowing(), "Closing a panel should return to the mascot");
    }
}
