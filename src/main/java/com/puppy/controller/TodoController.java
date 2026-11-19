package com.puppy.controller;

import com.puppy.enums.Priority;
import com.puppy.model.TaskRefinementReply;
import com.puppy.model.TaskRefinementTurn;
import com.puppy.model.TodoTask;
import com.puppy.service.AiTaskRefinementService;
import com.puppy.service.TodoService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** JavaFX presentation for the standalone to-do list and AI refinement flow. */
public final class TodoController {
    @FXML private TextField newTaskInput;
    @FXML private VBox taskList;
    @FXML private VBox refinementPane;
    @FXML private VBox conversationBox;
    @FXML private TextField refinementInput;
    @FXML private Button sendRefinementButton;
    @FXML private Label refinementStatus;
    @FXML private VBox proposalForm;
    @FXML private TextField titleField;
    @FXML private TextArea descriptionField;
    @FXML private TextField categoryField;
    @FXML private ComboBox<Priority> priorityChoice;
    @FXML private TextArea criteriaField;
    @FXML private TextArea checklistField;
    @FXML private TextField tagsField;
    @FXML private Button saveProposalButton;

    private final List<TaskRefinementTurn> history = new ArrayList<>();
    private TodoService todos;
    private AiTaskRefinementService refinement;
    private ExecutorService executor;
    private Runnable closeAction;
    private Runnable minimizeAction;
    private Runnable menuAction;
    private String originalTask;
    private TodoTask currentProposal;
    private AiTaskRefinementService.Intent intent = AiTaskRefinementService.Intent.REFINE;
    private boolean busy;

    @FXML
    public void initialize() {
        priorityChoice.getItems().setAll(Priority.values());
        priorityChoice.setValue(Priority.MEDIUM);
        refinementPane.setVisible(false);
        refinementPane.setManaged(false);
        proposalForm.setVisible(false);
        proposalForm.setManaged(false);
    }

    public void attach(TodoService todos, AiTaskRefinementService refinement,
                       ExecutorService executor, Runnable closeAction,
                       Runnable minimizeAction, Runnable menuAction) {
        this.todos = Objects.requireNonNull(todos, "todos");
        this.refinement = Objects.requireNonNull(refinement, "refinement");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.minimizeAction = Objects.requireNonNull(minimizeAction, "minimizeAction");
        this.menuAction = Objects.requireNonNull(menuAction, "menuAction");
        renderTasks();
    }

    @FXML
    private void onAddSimpleTask() {
        String title = newTaskInput.getText() == null ? "" : newTaskInput.getText().strip();
        if (title.isEmpty()) {
            setStatus("Enter a task first.", true);
            return;
        }
        TodoTask task = TodoTask.draft(title);
        runWrite(() -> todos.save(task), () -> {
            newTaskInput.clear();
            renderTasks();
            setStatus("Task saved.", false);
        });
    }

    @FXML
    private void onStartRefinement() {
        String value = newTaskInput.getText() == null ? "" : newTaskInput.getText().strip();
        if (value.isEmpty()) {
            setStatus("Enter a task to refine.", true);
            return;
        }
        begin(value, null, AiTaskRefinementService.Intent.REFINE,
                "Please refine this task. Ask only if essential information is missing.");
    }

    @FXML
    private void onSendRefinement() {
        String message = refinementInput.getText() == null ? "" : refinementInput.getText().strip();
        if (message.isEmpty() || busy) {
            return;
        }
        if (currentProposal != null) {
            try {
                currentProposal = taskFromForm(currentProposal.id(), currentProposal.completed());
            } catch (IllegalArgumentException e) {
                setStatus(e.getMessage(), true);
                return;
            }
        }
        refinementInput.clear();
        requestRefinement(message);
    }

    @FXML
    private void onSaveProposal() {
        if (currentProposal == null || busy) {
            return;
        }
        TodoTask approved;
        try {
            approved = taskFromForm(currentProposal.id(), currentProposal.completed());
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage(), true);
            return;
        }
        runWrite(() -> todos.save(approved), () -> {
            renderTasks();
            cancelRefinement();
            newTaskInput.clear();
            setStatus("Refined task saved after your confirmation.", false);
        });
    }

    @FXML
    private void onCancelRefinement() {
        if (!busy) {
            cancelRefinement();
            setStatus("Refinement cancelled. Nothing was saved.", false);
        }
    }

    @FXML private void onClose() { closeAction.run(); }
    @FXML private void onMinimize() { minimizeAction.run(); }
    @FXML private void onMainMenu() { menuAction.run(); }

    private void begin(String original, TodoTask proposal,
                       AiTaskRefinementService.Intent requestedIntent, String firstMessage) {
        originalTask = original;
        currentProposal = proposal;
        intent = requestedIntent;
        history.clear();
        conversationBox.getChildren().clear();
        refinementPane.setManaged(true);
        refinementPane.setVisible(true);
        if (proposal != null) {
            populateForm(proposal);
        } else {
            proposalForm.setManaged(false);
            proposalForm.setVisible(false);
        }
        requestRefinement(firstMessage);
    }

    private void requestRefinement(String userMessage) {
        if (busy) {
            return;
        }
        addConversation("You", userMessage, true);
        List<TaskRefinementTurn> transcript = List.copyOf(history);
        TodoTask proposalSnapshot = currentProposal;
        List<TodoTask> existing = todos.tasks();
        setBusy(true);
        refinementStatus.setText("Puppy is refining the task...");
        executor.submit(() -> {
            try {
                TaskRefinementReply reply = refinement.refine(originalTask, transcript,
                        proposalSnapshot, existing, userMessage, intent);
                Platform.runLater(() -> acceptReply(userMessage, reply));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setBusy(false);
                    setStatus(e.getMessage() == null ? "Task refinement failed." : e.getMessage(), true);
                });
            }
        });
    }

    private void acceptReply(String userMessage, TaskRefinementReply reply) {
        history.add(new TaskRefinementTurn(TaskRefinementTurn.Role.USER, userMessage));
        history.add(new TaskRefinementTurn(TaskRefinementTurn.Role.ASSISTANT, reply.message()));
        addConversation("Puppy", reply.message(), false);
        if (!reply.issues().isEmpty()) {
            addConversation("Review", String.join("\n", reply.issues()), false);
        }
        if (reply.kind() == TaskRefinementReply.Kind.PROPOSAL) {
            currentProposal = reply.proposal();
            populateForm(currentProposal);
            refinementStatus.setText("Review and edit the proposal, then confirm to save.");
        } else {
            refinementStatus.setText("Answer the question, or cancel without saving.");
            refinementInput.requestFocus();
        }
        setBusy(false);
    }

    private void populateForm(TodoTask task) {
        titleField.setText(task.title());
        descriptionField.setText(task.description());
        categoryField.setText(task.category());
        priorityChoice.setValue(task.priority());
        criteriaField.setText(task.completionCriteria());
        checklistField.setText(String.join("\n", task.checklist()));
        tagsField.setText(String.join(", ", task.tags()));
        proposalForm.setManaged(true);
        proposalForm.setVisible(true);
    }

    private TodoTask taskFromForm(String id, boolean completed) {
        String title = titleField.getText() == null ? "" : titleField.getText().strip();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("The task title cannot be empty.");
        }
        return new TodoTask(id == null ? UUID.randomUUID().toString() : id,
                title,
                value(descriptionField),
                categoryField.getText() == null || categoryField.getText().isBlank()
                        ? "General" : categoryField.getText(),
                priorityChoice.getValue() == null ? Priority.MEDIUM : priorityChoice.getValue(),
                value(criteriaField),
                lines(checklistField.getText()),
                commaValues(tagsField.getText()),
                completed);
    }

    private void renderTasks() {
        if (taskList == null || todos == null) {
            return;
        }
        taskList.getChildren().clear();
        List<TodoTask> items = todos.tasks();
        if (items.isEmpty()) {
            Label empty = new Label("No to-dos yet. Add one as-is or refine it with AI.");
            empty.getStyleClass().add("muted-copy");
            empty.setWrapText(true);
            taskList.getChildren().add(empty);
            return;
        }
        for (TodoTask task : items) {
            CheckBox done = new CheckBox();
            done.setSelected(task.completed());
            Label title = new Label(task.title());
            title.getStyleClass().add("todo-task-title");
            title.setWrapText(true);
            title.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(title, javafx.scene.layout.Priority.ALWAYS);
            Label metadata = new Label(task.category() + " · " + task.priority().label());
            metadata.getStyleClass().add("muted-copy");
            Button edit = smallButton("Edit", () -> openEditor(task));
            Button review = smallButton("AI review", () -> begin(describe(task), task,
                    AiTaskRefinementService.Intent.REVIEW,
                    "Review this existing task and suggest a clearer version."));
            Button breakdown = smallButton("Break down", () -> begin(describe(task), task,
                    AiTaskRefinementService.Intent.BREAK_DOWN,
                    "Break this task into smaller actionable steps."));
            Button delete = smallButton("Delete", () -> runWrite(() -> todos.delete(task.id()), () -> {
                renderTasks();
                setStatus("Task deleted.", false);
            }));
            HBox buttons = new HBox(4, edit, review, breakdown, delete);
            HBox top = new HBox(7, done, title);
            top.setAlignment(Pos.CENTER_LEFT);
            VBox card = new VBox(4, top, metadata, buttons);
            card.getStyleClass().add("todo-item");
            if (task.completed()) {
                card.getStyleClass().add("todo-item-completed");
            }
            done.setOnAction(event -> runWrite(
                    () -> todos.save(task.withCompleted(done.isSelected())), this::renderTasks));
            taskList.getChildren().add(card);
        }
    }

    private Button smallButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("message-action");
        button.setOnAction(event -> action.run());
        return button;
    }

    private void openEditor(TodoTask task) {
        originalTask = describe(task);
        currentProposal = task;
        intent = AiTaskRefinementService.Intent.REFINE;
        history.clear();
        conversationBox.getChildren().clear();
        addConversation("Puppy", "Edit any field below. Nothing changes until you confirm.", false);
        refinementPane.setManaged(true);
        refinementPane.setVisible(true);
        populateForm(task);
        refinementStatus.setText("You can also ask AI to refine your edited draft.");
    }

    private void addConversation(String speaker, String text, boolean user) {
        Label label = new Label(speaker + ": " + text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().addAll("refinement-message",
                user ? "refinement-user" : "refinement-assistant");
        conversationBox.getChildren().add(label);
    }

    private void runWrite(Runnable work, Runnable success) {
        if (busy) {
            return;
        }
        setBusy(true);
        executor.submit(() -> {
            try {
                work.run();
                Platform.runLater(() -> {
                    setBusy(false);
                    success.run();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setBusy(false);
                    setStatus(e.getMessage() == null ? "Could not save the task." : e.getMessage(), true);
                });
            }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        sendRefinementButton.setDisable(value);
        saveProposalButton.setDisable(value);
    }

    private void cancelRefinement() {
        originalTask = null;
        currentProposal = null;
        history.clear();
        conversationBox.getChildren().clear();
        refinementInput.clear();
        refinementPane.setVisible(false);
        refinementPane.setManaged(false);
        proposalForm.setVisible(false);
        proposalForm.setManaged(false);
    }

    private void setStatus(String message, boolean error) {
        refinementStatus.setText(message == null ? "" : message);
        refinementStatus.getStyleClass().remove("error-copy");
        if (error) {
            refinementStatus.getStyleClass().add("error-copy");
        }
    }

    private static String value(TextArea area) {
        return area.getText() == null ? "" : area.getText();
    }

    private static List<String> lines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\R"))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private static List<String> commaValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private static String describe(TodoTask task) {
        return task.title() + "\n" + task.description() + "\nDone when: "
                + task.completionCriteria();
    }
}
