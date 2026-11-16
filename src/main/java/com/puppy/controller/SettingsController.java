package com.puppy.controller;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;

import java.util.Objects;
import java.util.function.Consumer;

/** Settings that have a working in-session effect in the current milestone. */
public class SettingsController {

    @FXML
    private CheckBox alwaysOnTopCheck;

    @FXML
    private Label localModelLabel;

    @FXML
    private Label ollamaUrlLabel;

    @FXML
    private Label versionLabel;

    private Consumer<Boolean> alwaysOnTopChanged;
    private Runnable closeAction;
    private Runnable minimizeAction;
    private Runnable menuAction;

    public void attach(String appVersion, String localModel, String ollamaUrl,
                       boolean alwaysOnTop,
                       Consumer<Boolean> alwaysOnTopChanged,
                       Runnable closeAction, Runnable minimizeAction, Runnable menuAction) {
        this.alwaysOnTopChanged = Objects.requireNonNull(alwaysOnTopChanged, "alwaysOnTopChanged");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.minimizeAction = Objects.requireNonNull(minimizeAction, "minimizeAction");
        this.menuAction = Objects.requireNonNull(menuAction, "menuAction");
        alwaysOnTopCheck.setSelected(alwaysOnTop);
        localModelLabel.setText("Model: " + localModel);
        ollamaUrlLabel.setText("Server: " + ollamaUrl);
        versionLabel.setText("Version " + appVersion);
    }

    @FXML
    private void onAlwaysOnTopChanged() {
        alwaysOnTopChanged.accept(alwaysOnTopCheck.isSelected());
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
}
