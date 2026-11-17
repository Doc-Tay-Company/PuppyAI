package com.puppy.controller;

import com.puppy.ai.AiModel;
import com.puppy.service.ModelService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Settings with a working in-session effect: desktop behaviour, model choice
 * and the OpenRouter API key.
 *
 * <p>The key section is shown only while an OpenRouter model is selected; the
 * local model needs no key. The key is written only to Puppy's local data
 * directory. This controller never displays it, logs it, or puts it in a
 * label.</p>
 */
public class SettingsController {

    @FXML
    private CheckBox alwaysOnTopCheck;

    @FXML
    private ComboBox<AiModel> modelChoice;

    @FXML
    private Label modelLocationPill;

    @FXML
    private Label modelDetailLabel;

    @FXML
    private Label modelHintLabel;

    @FXML
    private Label aiStatusLabel;

    @FXML
    private VBox openRouterKeySection;

    @FXML
    private Label openRouterKeyPill;

    @FXML
    private Label openRouterKeyStatus;

    @FXML
    private PasswordField openRouterKeyField;

    @FXML
    private Button saveOpenRouterKeyButton;

    @FXML
    private Button removeOpenRouterKeyButton;

    @FXML
    private Label openRouterKeyHint;

    @FXML
    private Label localModelLabel;

    @FXML
    private Label ollamaUrlLabel;

    @FXML
    private Label versionLabel;

    private ModelService modelService;
    private Consumer<Boolean> alwaysOnTopChanged;
    private Runnable closeAction;
    private Runnable minimizeAction;
    private Runnable menuAction;
    private Runnable layoutChanged;
    private boolean refreshingModelChoice;

    public void attach(String appVersion, ModelService modelService, boolean alwaysOnTop,
                       Consumer<Boolean> alwaysOnTopChanged,
                       Runnable closeAction, Runnable minimizeAction, Runnable menuAction,
                       Runnable layoutChanged) {
        this.modelService = Objects.requireNonNull(modelService, "modelService");
        this.alwaysOnTopChanged = Objects.requireNonNull(alwaysOnTopChanged, "alwaysOnTopChanged");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.minimizeAction = Objects.requireNonNull(minimizeAction, "minimizeAction");
        this.menuAction = Objects.requireNonNull(menuAction, "menuAction");
        this.layoutChanged = Objects.requireNonNull(layoutChanged, "layoutChanged");
        alwaysOnTopCheck.setSelected(alwaysOnTop);
        localModelLabel.setText("Model: " + modelService.localModelDescription());
        ollamaUrlLabel.setText("Server: " + modelService.localServer());
        versionLabel.setText("Version " + appVersion);
        refreshAiSection();
    }

    private void refreshAiSection() {
        if (modelService == null) {
            return;
        }
        List<AiModel> models = modelService.availableModels();
        AiModel selected = modelService.selectedModel();

        refreshingModelChoice = true;
        try {
            modelChoice.getItems().setAll(models);
            modelChoice.setValue(models.stream()
                    .filter(model -> model.id().equals(selected.id()))
                    .findFirst()
                    .orElse(null));
        } finally {
            refreshingModelChoice = false;
        }

        boolean cloud = selected.cloud();
        modelLocationPill.setText(cloud ? "Cloud" : "Local");
        modelLocationPill.getStyleClass().removeAll("cloud-pill", "local-pill");
        modelLocationPill.getStyleClass().add(cloud ? "cloud-pill" : "local-pill");
        modelDetailLabel.setText(cloud
                ? "OpenRouter model: " + selected.modelName()
                : "Local model: " + selected.displayName());

        if (cloud) {
            if (modelService.hasOpenRouterKey()) {
                modelHintLabel.setText(
                        "Cloud replies go through OpenRouter. Attached document text is sent to "
                                + "OpenRouter and its model provider.");
            } else {
                modelHintLabel.setText(
                        "Add your OpenRouter API key below to use this model. The local model "
                                + "works without one.");
            }
        } else {
            modelHintLabel.setText(
                    "The local model needs no API key and keeps chat on this computer. Choose an "
                            + "OpenRouter model above for cloud replies.");
        }

        openRouterKeySection.setVisible(cloud);
        openRouterKeySection.setManaged(cloud);
        if (cloud) {
            updateOpenRouterKeySection();
        } else {
            openRouterKeyField.clear();
        }
        notifyLayoutChanged();
    }

    private void updateOpenRouterKeySection() {
        boolean fromEnvironment = modelService.isOpenRouterKeyFromEnvironment();
        boolean configured = modelService.hasOpenRouterKey();

        if (fromEnvironment) {
            openRouterKeyPill.setText("Environment");
            openRouterKeyStatus.setText(
                    "OpenRouter API key: configured from your environment.");
            openRouterKeyField.clear();
            openRouterKeyField.setPromptText("Managed by your environment");
            openRouterKeyField.setDisable(true);
            saveOpenRouterKeyButton.setDisable(true);
            removeOpenRouterKeyButton.setDisable(true);
            openRouterKeyHint.setText(
                    "Puppy reads OPENROUTER_API_KEY or PUPPY_OPENROUTER_API_KEY at startup. "
                            + "Remove it from the environment to manage the key here.");
            return;
        }

        openRouterKeyField.setDisable(false);
        saveOpenRouterKeyButton.setDisable(false);
        if (configured) {
            openRouterKeyPill.setText("Configured");
            openRouterKeyStatus.setText("OpenRouter API key: saved on this computer.");
            openRouterKeyField.setPromptText("Paste a new key to replace the saved one");
            removeOpenRouterKeyButton.setDisable(false);
            openRouterKeyHint.setText(
                    "Stored only in Puppy's local data folder on this computer. It is never "
                            + "written to the project, docs or logs.");
        } else {
            openRouterKeyPill.setText("Not configured");
            openRouterKeyStatus.setText("OpenRouter API key: not configured.");
            openRouterKeyField.setPromptText("Paste your OpenRouter API key");
            removeOpenRouterKeyButton.setDisable(true);
            openRouterKeyHint.setText(
                    "The key is stored only on this computer in Puppy's local data folder, never "
                            + "in the project. The local model needs no key.");
        }
    }

    private void notifyLayoutChanged() {
        if (layoutChanged != null) {
            layoutChanged.run();
        }
    }

    private void showStatus(String message, boolean error) {
        aiStatusLabel.setText(message);
        aiStatusLabel.getStyleClass().remove("error-copy");
        if (error) {
            aiStatusLabel.getStyleClass().add("error-copy");
        }
        aiStatusLabel.setManaged(true);
        aiStatusLabel.setVisible(true);
        notifyLayoutChanged();
    }

    @FXML
    private void onModelChanged() {
        if (refreshingModelChoice || modelService == null) {
            return;
        }
        AiModel chosen = modelChoice.getValue();
        if (chosen == null) {
            return;
        }
        try {
            modelService.selectModel(chosen.id());
            refreshAiSection();
            if (chosen.cloud() && !modelService.hasOpenRouterKey()) {
                showStatus("Selected " + chosen.displayName()
                        + ". Add your OpenRouter API key below.", false);
            } else {
                showStatus("Now using " + chosen.displayName()
                        + " (" + chosen.locationLabel() + ").", false);
            }
        } catch (IOException e) {
            showStatus("Puppy couldn't save the model choice. Check access to its local data folder.",
                    true);
        } catch (IllegalArgumentException | IllegalStateException e) {
            showStatus(e.getMessage(), true);
        }
    }

    @FXML
    private void onSaveOpenRouterKey() {
        if (modelService == null || !modelService.canEditOpenRouterKey()) {
            return;
        }
        try {
            modelService.saveOpenRouterKey(openRouterKeyField.getText());
            openRouterKeyField.clear();
            refreshAiSection();
            showStatus("OpenRouter API key saved.", false);
        } catch (IllegalArgumentException | IllegalStateException e) {
            showStatus(e.getMessage(), true);
        } catch (IOException e) {
            showStatus("Puppy couldn't save the key. Check access to its local data folder.", true);
        }
    }

    @FXML
    private void onRemoveOpenRouterKey() {
        if (modelService == null || !modelService.canEditOpenRouterKey()) {
            return;
        }
        try {
            modelService.clearOpenRouterKey();
            openRouterKeyField.clear();
            refreshAiSection();
            showStatus("OpenRouter key removed. Add a new key or choose the local model.",
                    false);
        } catch (IllegalArgumentException | IllegalStateException e) {
            showStatus(e.getMessage(), true);
        } catch (IOException e) {
            showStatus("Puppy couldn't remove the key. Check access to its local data folder.", true);
        }
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
