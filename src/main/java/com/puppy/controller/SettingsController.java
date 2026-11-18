package com.puppy.controller;

import com.puppy.ai.AiModel;
import com.puppy.service.ModelService;
import com.puppy.ui.ModelBrowserDialog;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    private static final DateTimeFormatter CATALOG_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
                    .withZone(ZoneId.systemDefault());

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
    private Button refreshModelsButton;

    @FXML
    private Label modelCatalogStatus;

    @FXML
    private Label modelWarningLabel;

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
    private java.util.concurrent.ExecutorService executor;
    private com.puppy.service.AppSettingsService appSettings;

    public void attachPersistence(java.util.concurrent.ExecutorService executor,
                                  com.puppy.service.AppSettingsService appSettings) {
        this.executor = executor;
        this.appSettings = appSettings;
    }

    @FunctionalInterface
    private interface SettingsAction { void run() throws IOException; }

    private void saveSetting(SettingsAction action, Runnable success) {
        Runnable work = () -> {
            try {
                action.run();
                runOnUi(success);
            } catch (IOException | RuntimeException e) {
                runOnUi(() -> {
                    refreshAiSection();
                    if (appSettings != null) {
                        alwaysOnTopCheck.setSelected(appSettings.alwaysOnTop());
                    }
                    showStatus("Setting was not saved. Check local storage access and retry.", true);
                });
            }
        };
        if (executor == null) {
            work.run();
        } else {
            try {
                executor.submit(work);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                showStatus("The application is shutting down; the setting was not changed.", true);
            }
        }
    }

    private void runOnUi(Runnable action) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            action.run();
        } else {
            javafx.application.Platform.runLater(action);
        }
    }

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
        List<AiModel> models = modelService.modelPickerModels();
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
        if (cloud) {
            StringBuilder detail = new StringBuilder("OpenRouter model: ")
                    .append(selected.modelName());
            if (selected.contextLength() > 0) {
                detail.append(" · ").append(selected.details().contextLabel()).append(" context");
            }
            String price = selected.pricingLabel();
            if (price != null && !price.isBlank()) {
                detail.append(" · ").append(price);
            }
            if (!selected.supportsJson()) {
                detail.append(" · JSON via prompt fallback");
            }
            modelDetailLabel.setText(detail.toString());
        } else {
            modelDetailLabel.setText("Local model: " + selected.displayName());
        }

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

        updateCatalogStatus();

        openRouterKeySection.setVisible(cloud);
        openRouterKeySection.setManaged(cloud);
        if (cloud) {
            updateOpenRouterKeySection();
        } else {
            openRouterKeyField.clear();
        }
        notifyLayoutChanged();
    }

    /** Shows the cached-list timestamp and any warning about a missing saved model. */
    private void updateCatalogStatus() {
        if (modelCatalogStatus == null || modelService == null) {
            return;
        }
        Optional<Instant> fetchedAt = modelService.catalogFetchedAt();
        int count = modelService.catalogModelCount();
        if (fetchedAt.isPresent() && count > 0) {
            String staleNote = modelService.isCatalogStale()
                    ? " · refresh recommended"
                    : "";
            modelCatalogStatus.setText("Updated " + CATALOG_TIME.format(fetchedAt.get())
                    + " · " + count + " OpenRouter models cached" + staleNote + ".");
        } else {
            modelCatalogStatus.setText(
                    "Not updated yet · showing built-in suggestions. "
                            + "Choose Refresh models to load the full OpenRouter list.");
        }

        String warning = modelService.selectionWarning();
        if (modelWarningLabel != null) {
            boolean visible = warning != null && !warning.isBlank();
            modelWarningLabel.setText(visible ? warning : "");
            modelWarningLabel.setVisible(visible);
            modelWarningLabel.setManaged(visible);
            modelWarningLabel.getStyleClass().remove("error-copy");
            if (visible) {
                modelWarningLabel.getStyleClass().add("error-copy");
            }
        }
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
        selectModel(chosen);
    }

    @FXML
    private void onBrowseModels() {
        if (modelService == null) {
            return;
        }
        Window owner = modelChoice.getScene() == null
                ? null
                : modelChoice.getScene().getWindow();
        Optional<AiModel> chosen = ModelBrowserDialog.show(
                owner, modelService.availableModels(), modelService.selectedModel());
        chosen.ifPresent(this::selectModel);
    }

    @FXML
    private void onRefreshModels() {
        if (modelService == null) {
            return;
        }
        refreshModelsButton.setDisable(true);
        modelCatalogStatus.setText("Refreshing the OpenRouter model list…");
        modelService.refreshModelCatalog().whenComplete((result, failure) -> runOnUi(() -> {
            refreshModelsButton.setDisable(false);
            refreshAiSection();
            if (failure != null || result == null || !result.success()) {
                String detail = result != null && result.message() != null
                        ? " " + result.message()
                        : " Check your internet connection.";
                String unchanged = detail.contains("unchanged") || detail.contains("Keeping")
                        ? ""
                        : " The previous list is unchanged.";
                showStatus("Couldn't refresh the model list." + detail + unchanged, true);
            } else if (result.message() != null && !result.message().isBlank()) {
                showStatus(result.message(), true);
            } else {
                showStatus("Model list updated: " + result.modelCount()
                        + " OpenRouter models available.", false);
            }
        }));
    }

    private void selectModel(AiModel chosen) {
        if (chosen == null || modelService == null) {
            return;
        }
        saveSetting(() -> modelService.selectModel(chosen.id()), () -> {
            refreshAiSection();
            if (chosen.cloud() && !modelService.hasOpenRouterKey()) {
                showStatus("Selected " + chosen.displayName()
                        + ". Add your OpenRouter API key below.", false);
            } else {
                showStatus("Now using " + chosen.displayName()
                        + " (" + chosen.locationLabel() + ").", false);
            }
        });
    }

    @FXML
    private void onSaveOpenRouterKey() {
        if (modelService == null || !modelService.canEditOpenRouterKey()) {
            return;
        }
        String key = openRouterKeyField.getText();
        saveSetting(() -> modelService.saveOpenRouterKey(key), () -> {
            openRouterKeyField.clear();
            refreshAiSection();
            showStatus("OpenRouter API key saved.", false);
        });
    }

    @FXML
    private void onRemoveOpenRouterKey() {
        if (modelService == null || !modelService.canEditOpenRouterKey()) {
            return;
        }
        saveSetting(modelService::clearOpenRouterKey, () -> {
            openRouterKeyField.clear();
            refreshAiSection();
            showStatus("OpenRouter key removed. Add a new key or choose the local model.",
                    false);
        });
    }

    @FXML
    private void onAlwaysOnTopChanged() {
        boolean value = alwaysOnTopCheck.isSelected();
        saveSetting(() -> {
            if (appSettings != null) {
                appSettings.setAlwaysOnTop(value);
            }
        }, () -> alwaysOnTopChanged.accept(value));
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
