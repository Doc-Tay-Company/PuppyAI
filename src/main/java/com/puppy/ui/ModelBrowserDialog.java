package com.puppy.ui;

import com.puppy.ai.AiModel;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The full-catalog picker opened from Settings.
 *
 * <p>The combo box intentionally shows only a few suggestions. This dialog
 * holds the complete OpenRouter list (often 300+ entries) with search and
 * filters for cloud, free, JSON-capable and context size.</p>
 */
public final class ModelBrowserDialog {

    private static final String CLOUD_ONLY = "Cloud only";
    private static final String FREE_ONLY = "Free only";
    private static final String JSON_ONLY = "JSON-capable";

    private ModelBrowserDialog() {
    }

    public static Optional<AiModel> show(Window owner, List<AiModel> models, AiModel selected) {
        Objects.requireNonNull(models, "models");

        Dialog<AiModel> dialog = new Dialog<>();
        dialog.setTitle("Browse all models");
        dialog.setHeaderText("Choose any model Puppy can use. "
                + "Cloud models need an OpenRouter key.");
        dialog.setResizable(true);

        ButtonType use = new ButtonType("Use model", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(use, ButtonType.CANCEL);

        TextField search = new TextField();
        search.setPromptText("Search by name or id");
        search.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(search, Priority.ALWAYS);

        CheckBox cloudOnly = new CheckBox(CLOUD_ONLY);
        CheckBox freeOnly = new CheckBox(FREE_ONLY);
        CheckBox jsonOnly = new CheckBox(JSON_ONLY);
        ComboBox<ContextFilter> context = new ComboBox<>(
                FXCollections.observableArrayList(ContextFilter.values()));
        context.setValue(ContextFilter.ANY);

        Label summary = new Label();
        summary.getStyleClass().add("muted-copy");

        List<AiModel> sorted = models.stream()
                .sorted(Comparator.comparing(AiModel::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AiModel::modelName))
                .toList();
        FilteredList<AiModel> filtered =
                new FilteredList<>(FXCollections.observableArrayList(sorted), model -> true);
        TableView<AiModel> table = new TableView<>(filtered);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No models match those filters."));

        TableColumn<AiModel, String> nameColumn = new TableColumn<>("Model");
        nameColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().displayName()));
        nameColumn.setPrefWidth(160);

        TableColumn<AiModel, String> idColumn = new TableColumn<>("Id");
        idColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().modelName()));
        idColumn.setPrefWidth(210);

        TableColumn<AiModel, String> contextColumn = new TableColumn<>("Context");
        contextColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().contextLength() <= 0
                        ? "Unknown"
                        : cell.getValue().details().contextLabel()));
        contextColumn.setPrefWidth(85);

        TableColumn<AiModel, String> priceColumn = new TableColumn<>("Price");
        priceColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().pricingLabel()));
        priceColumn.setPrefWidth(175);

        TableColumn<AiModel, String> tagsColumn = new TableColumn<>("Tags");
        tagsColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(tags(cell.getValue())));
        tagsColumn.setPrefWidth(140);

        table.getColumns().addAll(nameColumn, idColumn, contextColumn, priceColumn, tagsColumn);
        VBox.setVgrow(table, Priority.ALWAYS);

        Runnable updateFilter = () -> {
            filtered.setPredicate(model -> matches(
                    model, search.getText(), cloudOnly.isSelected(), freeOnly.isSelected(),
                    jsonOnly.isSelected(), context.getValue()));
            summary.setText(filtered.size() + " of " + sorted.size() + " models");
        };
        search.textProperty().addListener((observable, oldValue, newValue) -> updateFilter.run());
        cloudOnly.selectedProperty().addListener((observable, oldValue, newValue) ->
                updateFilter.run());
        freeOnly.selectedProperty().addListener((observable, oldValue, newValue) ->
                updateFilter.run());
        jsonOnly.selectedProperty().addListener((observable, oldValue, newValue) ->
                updateFilter.run());
        context.valueProperty().addListener((observable, oldValue, newValue) ->
                updateFilter.run());
        updateFilter.run();

        HBox filters = new HBox(12, cloudOnly, freeOnly, jsonOnly, context);
        filters.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox content = new VBox(8, search, filters, summary, table);
        content.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(780, 520);
        dialog.getDialogPane().getStyleClass().add("model-browser");

        Node useButton = dialog.getDialogPane().lookupButton(use);
        useButton.disableProperty().bind(
                Bindings.isNull(table.getSelectionModel().selectedItemProperty()));

        table.setOnMouseClicked(event -> {
            AiModel chosen = table.getSelectionModel().getSelectedItem();
            if (event.getClickCount() == 2 && chosen != null) {
                dialog.setResult(chosen);
                dialog.close();
            }
        });
        dialog.setResultConverter(button -> button == use
                ? table.getSelectionModel().getSelectedItem()
                : null);
        dialog.setOnShown(event -> {
            if (selected != null && filtered.contains(selected)) {
                table.getSelectionModel().select(selected);
                table.scrollTo(selected);
            }
            search.requestFocus();
        });

        if (owner != null) {
            dialog.initOwner(owner);
            if (owner.getScene() != null) {
                dialog.getDialogPane().getStylesheets()
                        .addAll(owner.getScene().getStylesheets());
            }
        }
        return dialog.showAndWait();
    }

    private static boolean matches(AiModel model, String query, boolean cloudOnly,
                                   boolean freeOnly, boolean jsonOnly, ContextFilter context) {
        if (cloudOnly && !model.cloud()) {
            return false;
        }
        if (freeOnly && !model.free()) {
            return false;
        }
        if (jsonOnly && !model.supportsJson()) {
            return false;
        }
        if (context != null && context.minimumContext() > 0
                && model.contextLength() < context.minimumContext()) {
            return false;
        }
        String text = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return true;
        }
        return model.displayName().toLowerCase(Locale.ROOT).contains(text)
                || model.modelName().toLowerCase(Locale.ROOT).contains(text)
                || model.id().toLowerCase(Locale.ROOT).contains(text)
                || model.providerName().toLowerCase(Locale.ROOT).contains(text);
    }

    private static String tags(AiModel model) {
        StringBuilder tags = new StringBuilder(model.cloud() ? "Cloud" : "Local");
        if (model.free()) {
            tags.append(", Free");
        }
        if (model.supportsJson()) {
            tags.append(", JSON");
        } else {
            tags.append(", JSON fallback");
        }
        return tags.toString();
    }

    private enum ContextFilter {
        ANY("Any context", 0),
        K16("16k+", 16_384),
        K32("32k+", 32_768),
        K128("128k+", 131_072),
        M1("1M+", 1_000_000);

        private final String label;
        private final int minimumContext;

        ContextFilter(String label, int minimumContext) {
            this.label = label;
            this.minimumContext = minimumContext;
        }

        int minimumContext() {
            return minimumContext;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
