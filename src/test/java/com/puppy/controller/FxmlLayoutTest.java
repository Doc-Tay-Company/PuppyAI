package com.puppy.controller;

import com.puppy.config.AppConfig;
import com.puppy.config.ServiceRegistry;
import com.puppy.enums.MascotState;
import com.puppy.ui.MarkdownRenderer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxmlLayoutTest {

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
                assertFalse(shell.lookup(".menu-host").isVisible(),
                        "Dragging must not trigger the double-click menu");

                double expectedRight = stage.getX() + stage.getWidth();
                double expectedBottom = stage.getY() + stage.getHeight();
                Event.fireEvent(mascotDock, primaryPress(30, 30));
                Event.fireEvent(mascotDock, doubleClick());
                assertTrue(shell.lookup(".menu-host").isVisible(),
                        "Double-click should open the main menu");
                Event.fireEvent(shell, new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                        KeyCode.ESCAPE, false, false, false, false));
                assertFalse(shell.lookup(".menu-host").isVisible(),
                        "Escape should dismiss the menu");
                Event.fireEvent(mascotDock, doubleClick());
                double anchoredRight = stage.getX() + stage.getWidth();
                double anchoredBottom = stage.getY() + stage.getHeight();
                assertTrue(stage.getWidth() > 336, "The menu should expand the shell");
                double menuWidth = stage.getWidth();
                double menuHeight = stage.getHeight();
                assertTrue(Math.abs(anchoredRight - expectedRight) < 1,
                        "The expanded shell should preserve its right edge");
                assertTrue(Math.abs(anchoredBottom - expectedBottom) < 1,
                        "The expanded shell should preserve its bottom edge");
                HBox menu = (HBox) shell.lookup(".main-menu");

                ((Button) menu.getChildren().get(0)).fire();
                assertTrue(shell.lookup(".panel-host").isVisible(),
                        "Study should open in the panel host");
                assertEquals(menuWidth, stage.getWidth(),
                        "Study should use the same shell width as the menu");
                assertTrue(stage.getHeight() > menuHeight,
                        "Study should leave room for the mascot below the panel");
                assertEquals("Study Mode",
                        ((Label) shell.lookup(".study-header .panel-title")).getText(),
                        "The Study panel title should identify Study Mode");
                assertNull(shell.lookup(".study-footer"),
                        "The separate Study footer should be removed");
                Button studyToggle = (Button) shell.lookup("#startPauseButton");
                assertTrue(stage.getScene().getWidth() > 340,
                        "The scene should grow with the panel window");
                Node panelNode = shell.lookup(".panel-host");
                Bounds panelBounds = panelNode.localToScene(panelNode.getBoundsInLocal());
                Bounds toggleBounds = studyToggle.localToScene(studyToggle.getBoundsInLocal());
                assertTrue(panelBounds.contains(toggleBounds),
                        "The timer button must stay inside the visible panel to be clickable");
                assertTrue(mascotDock.isVisible(),
                        "The mascot should stay visible while a panel is open");
                Bounds dockBounds = mascotDock.localToScene(mascotDock.getBoundsInLocal());
                assertFalse(panelBounds.intersects(dockBounds),
                        "The panel must not cover the mascot");
                studyToggle.fire();
                assertTrue("Pause".equals(studyToggle.getText()),
                        "Starting a session should switch the button to Pause");
                assertTrue(services.mascotStateService().getState() == MascotState.STUDYING,
                        "Starting a session should animate the mascot as studying");
                studyToggle.fire();
                assertTrue("Resume".equals(studyToggle.getText()),
                        "Pausing a session should offer Resume");
                closeActivePanel(shell, 0);

                Event.fireEvent(mascotDock, doubleClick());
                ((Button) menu.getChildren().get(1)).fire();
                shell.applyCss();
                shell.layout();
                assertTrue(shell.lookup(".panel-host").isVisible(),
                        "Focus should open in the panel host");
                assertEquals("Focus Planner",
                        ((Label) shell.lookup(".focus-panel .panel-title")).getText(),
                        "The Focus panel title should identify the planner");
                assertTrue(shell.lookup("#sessionsBox").isVisible(),
                        "Focus should show the session list");
                closeActivePanel(shell, 0);

                Event.fireEvent(mascotDock, doubleClick());
                ((Button) menu.getChildren().get(2)).fire();
                assertTrue(shell.lookup(".panel-host").isVisible(),
                        "Chat should open in the panel host");
                shell.applyCss();
                shell.layout();
                VBox messages = (VBox) shell.lookup("#messagesBox");
                TextField messageInput = (TextField) shell.lookup("#messageInput");
                int messageCount = messages.getChildren().size();
                messageInput.setText("   ");
                ((Button) shell.lookup("#sendButton")).fire();
                assertEquals(messageCount, messages.getChildren().size(),
                        "Blank chat input must not add a message");
                messageInput.setText("/model");
                ((Button) shell.lookup("#sendButton")).fire();
                assertEquals(messageCount + 2, messages.getChildren().size(),
                        "/model should answer locally without contacting Ollama");
                closeActivePanel(shell, 1);

                Event.fireEvent(mascotDock, doubleClick());
                ((Button) menu.getChildren().get(3)).fire();
                VBox moreMenu = (VBox) ((StackPane) shell.lookup(".menu-host")).getChildren().get(0);
                ((Button) moreMenu.getChildren().get(2)).fire();
                assertTrue(shell.lookup(".panel-host").isVisible(),
                        "Settings should open from More");
                CheckBox alwaysOnTop = (CheckBox) shell.lookup("#alwaysOnTopCheck");
                alwaysOnTop.fire();
                assertTrue(stage.isAlwaysOnTop(),
                        "The in-session always-on-top setting should update the stage");
                closeActivePanel(shell, 1);

                Event.fireEvent(mascotDock, doubleClick());
                ((Button) menu.getChildren().get(3)).fire();
                moreMenu = (VBox) ((StackPane) shell.lookup(".menu-host")).getChildren().get(0);
                ((Button) moreMenu.getChildren().get(3)).fire();
                assertTrue(shell.lookup("#historyPane").isVisible(),
                        "More should open session chat history");
                closeActivePanel(shell, 1);
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

    private static void closeActivePanel(Parent shell, int buttonIndex) {
        StackPane panelHost = (StackPane) shell.lookup(".panel-host");
        Parent panel = (Parent) panelHost.getChildren().get(0);
        List<Button> windowButtons = panel.lookupAll(".window-button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .toList();
        windowButtons.get(buttonIndex).fire();
        assertFalse(panelHost.isVisible(), "Closing a panel should return to the mascot");
    }
}
