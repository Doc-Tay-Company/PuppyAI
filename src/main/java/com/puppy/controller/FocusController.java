package com.puppy.controller;

import com.puppy.enums.FocusType;
import com.puppy.enums.MascotState;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.FocusPlan;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.StudyTask;
import com.puppy.service.FocusFormats;
import com.puppy.service.FocusPlanningException;
import com.puppy.service.FocusService;
import com.puppy.service.MascotStateService;
import com.puppy.service.StudyTimer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Focus Planner panel.
 *
 * <p>The left column collects timetable, task and free-time data (imported from
 * a file or typed by hand). The right column shows the generated sessions,
 * their type and completion state, and an optional countdown for the active
 * session.</p>
 */
public class FocusController {

    @FXML
    private VBox focusRoot;

    @FXML
    private ComboBox<String> availableFrom;

    @FXML
    private ComboBox<String> availableTo;

    @FXML
    private Label availabilityStatus;

    @FXML
    private TabPane plannerTabs;

    @FXML
    private Button importButton;

    @FXML
    private Label importStatus;

    @FXML
    private Button generatePlanButton;

    @FXML
    private Label plannerStatus;

    @FXML
    private TextField classSubject;

    @FXML
    private ComboBox<DayOfWeek> classDay;

    @FXML
    private TextField classStart;

    @FXML
    private TextField classEnd;

    @FXML
    private VBox classList;

    @FXML
    private TextField taskSubject;

    @FXML
    private TextField taskTitle;

    @FXML
    private ComboBox<FocusType> taskType;

    @FXML
    private TextField taskMinutes;

    @FXML
    private DatePicker taskDeadline;

    @FXML
    private VBox taskList;

    @FXML
    private Tab sessionTab;

    @FXML
    private Label sessionFormHint;

    @FXML
    private TextField sessionSubject;

    @FXML
    private TextField sessionTitle;

    @FXML
    private ComboBox<FocusType> sessionType;

    @FXML
    private DatePicker sessionDate;

    @FXML
    private TextField sessionStart;

    @FXML
    private TextField sessionMinutes;

    @FXML
    private Button sessionSaveButton;

    @FXML
    private Button cancelEditButton;

    @FXML
    private Label sessionStatus;

    @FXML
    private Label timerTitle;

    @FXML
    private Label timerMeta;

    @FXML
    private Label timerTime;

    @FXML
    private Label timerHint;

    @FXML
    private Button timerToggle;

    @FXML
    private Button timerFinish;

    @FXML
    private Label sessionCount;

    @FXML
    private Label planSummary;

    @FXML
    private Label warningsLabel;

    @FXML
    private VBox sessionsBox;

    @FXML
    private ScrollPane sessionsScroll;

    @FXML
    private Button clearPlanButton;

    private FocusService focusService;
    private MascotStateService mascotStateService;
    private ExecutorService executor;
    private Runnable closeAction;
    private Runnable minimizeAction;
    private Runnable menuAction;

    private Timeline ticker;
    private PauseTransition happyReaction;
    private Future<?> inFlight;
    private int importGeneration;
    private boolean importing;
    private boolean saving;
    private boolean closed;
    private boolean completionFailed;

    private FocusSession activeSession;
    private FocusSession editingSession;
    private StudyTimer timer;
    private boolean paused;

    @FXML
    public void initialize() {
        availableFrom.getItems().setAll(halfHourOptions());
        availableTo.getItems().setAll(halfHourOptions());
        availableFrom.setValue("08:00");
        availableTo.setValue("22:00");

        classDay.getItems().setAll(DayOfWeek.values());
        classDay.setConverter(new StringConverter<>() {
            @Override
            public String toString(DayOfWeek day) {
                return day == null ? "" : day.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            }

            @Override
            public DayOfWeek fromString(String text) {
                return text == null || text.isBlank() ? null : FocusFormats.parseDay(text);
            }
        });

        taskType.getItems().setAll(FocusType.values());
        taskType.setValue(FocusType.QUICK);
        sessionType.getItems().setAll(FocusType.values());
        sessionType.setValue(FocusType.QUICK);
        sessionDate.setValue(LocalDate.now());
        resetSessionForm();

        ticker = new Timeline(new KeyFrame(Duration.millis(200), event -> tick()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();

        happyReaction = new PauseTransition(Duration.seconds(1.4));
        happyReaction.setOnFinished(event -> {
            if (timer == null || !timer.isRunning()) {
                mascotStateService.resetToIdle();
            }
        });

        updateTimerCard();
        setStatus(plannerStatus,
                "Import a timetable or add classes and tasks, then generate a plan.", false);
    }

    public void attach(FocusService focusService, MascotStateService mascotStateService,
                       ExecutorService executor, Runnable closeAction, Runnable minimizeAction,
                       Runnable menuAction) {
        this.focusService = Objects.requireNonNull(focusService, "focusService");
        this.mascotStateService = Objects.requireNonNull(mascotStateService, "mascotStateService");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.minimizeAction = Objects.requireNonNull(minimizeAction, "minimizeAction");
        this.menuAction = Objects.requireNonNull(menuAction, "menuAction");
        loadPreferences();
        refresh();
    }

    private void loadPreferences() {
        FocusPreferences preferences = focusService.preferences();
        availableFrom.setValue(FocusFormats.formatTime(preferences.earliest()));
        availableTo.setValue(FocusFormats.formatTime(preferences.latest()));
        availabilityStatus.setText("Sessions stay inside " + availableFrom.getValue()
                + " - " + availableTo.getValue() + " and avoid every class.");
    }

    /** Reloads the free-time selectors after Chat set a new window. */
    public void refreshPreferences() {
        if (focusService == null) {
            return;
        }
        loadPreferences();
    }

    /** Reloads every list from the shared service. Public for the chat focus flow. */
    public void refresh() {
        if (focusService == null) {
            return;
        }
        ensureActiveSessionExists();
        ensureEditingSessionExists();
        renderClasses();
        renderTasks();
        renderSessions();
        updateSummary();
        updateTimerCard();
    }

    private void ensureActiveSessionExists() {
        if (activeSession == null) {
            return;
        }
        boolean exists = focusService.sessions().stream()
                .anyMatch(session -> session.id().equals(activeSession.id()));
        if (!exists) {
            activeSession = null;
            timer = null;
            paused = false;
            updateTimerCard();
        }
    }

    private void ensureEditingSessionExists() {
        if (editingSession == null) {
            return;
        }
        boolean exists = focusService.sessions().stream()
                .anyMatch(session -> session.id().equals(editingSession.id()));
        if (!exists) {
            resetSessionForm();
            setStatus(sessionStatus,
                    "That session is no longer in the plan. Editing cancelled.", true);
        }
    }

    private void renderClasses() {
        classList.getChildren().clear();
        var classes = focusService.classes();
        if (classes.isEmpty()) {
            classList.getChildren().add(muted("No classes yet. Add your weekly classes here."));
            return;
        }
        for (ClassSlot classSlot : classes) {
            HBox row = new HBox(6);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("focus-list-item");

            Label label = new Label(FocusFormats.formatDay(classSlot.day()) + " "
                    + FocusFormats.formatTime(classSlot.start()) + "-"
                    + FocusFormats.formatTime(classSlot.end()) + "  "
                    + classSlot.subject());
            label.getStyleClass().add("focus-list-text");
            label.setWrapText(true);
            HBox.setHgrow(label, javafx.scene.layout.Priority.ALWAYS);

            Button remove = new Button("x");
            remove.getStyleClass().add("attachment-remove");
            remove.setOnAction(event -> {
                mutate(plannerStatus, () -> {
                    focusService.removeClass(classSlot);
                    return null;
                }, ignored -> setStatus(plannerStatus, "Class removed.", false));
            });
            row.getChildren().addAll(label, remove);
            classList.getChildren().add(row);
        }
    }

    private void renderTasks() {
        taskList.getChildren().clear();
        var tasks = focusService.tasks();
        if (tasks.isEmpty()) {
            taskList.getChildren().add(muted("No tasks yet. Add assignments and reviews here."));
            return;
        }
        for (StudyTask task : tasks) {
            HBox row = new HBox(6);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("focus-list-item");

            String deadline = task.deadline() == null
                    ? "no deadline"
                    : "due " + FocusFormats.formatDate(task.deadline());
            Label label = new Label(task.subject() + " - " + task.title() + "\n"
                    + task.type().label() + " - " + task.estimatedMinutes() + "m - "
                    + deadline);
            label.getStyleClass().add("focus-list-text");
            label.setWrapText(true);
            HBox.setHgrow(label, javafx.scene.layout.Priority.ALWAYS);

            Button remove = new Button("x");
            remove.getStyleClass().add("attachment-remove");
            remove.setOnAction(event -> {
                mutate(plannerStatus, () -> {
                    focusService.removeTask(task);
                    return null;
                }, ignored -> setStatus(plannerStatus, "Task removed.", false));
            });
            row.getChildren().addAll(label, remove);
            taskList.getChildren().add(row);
        }
    }

    private void renderSessions() {
        sessionsBox.getChildren().clear();
        FocusPlan plan = focusService.currentPlan();
        if (plan.sessions().isEmpty()) {
            sessionsBox.getChildren().add(muted(
                    "No sessions planned yet. Import a file or add tasks, then press Generate plan."));
            return;
        }

        Map<LocalDate, java.util.List<FocusSession>> byDate = new TreeMap<>();
        for (FocusSession session : plan.sessions()) {
            byDate.computeIfAbsent(session.date(), key -> new ArrayList<>()).add(session);
        }
        for (Map.Entry<LocalDate, java.util.List<FocusSession>> entry : byDate.entrySet()) {
            Label day = new Label(FocusFormats.formatDate(entry.getKey()));
            day.getStyleClass().add("focus-day-header");
            sessionsBox.getChildren().add(day);
            for (FocusSession session : entry.getValue()) {
                sessionsBox.getChildren().add(sessionRow(session));
            }
        }
    }

    private HBox sessionRow(FocusSession session) {
        boolean active = activeSession != null && activeSession.id().equals(session.id());
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("focus-session");
        if (active) {
            row.getStyleClass().add("focus-session-active");
        }
        if (session.completed()) {
            row.getStyleClass().add("focus-session-completed");
        }

        Label time = new Label(session.timeRange());
        time.getStyleClass().add("focus-session-time");
        time.setMinWidth(104);

        Label subject = new Label(session.subject());
        subject.getStyleClass().add("focus-session-subject");
        Label title = new Label(session.title());
        title.getStyleClass().add("muted-copy");
        title.setWrapText(true);
        title.setMaxWidth(340);

        HBox badges = new HBox(4);
        badges.getChildren().add(badge(session.type().label(),
                session.type() == FocusType.DEEP ? "focus-type-deep" : "focus-type-quick"));
        if (session.completed()) {
            badges.getChildren().add(badge("Completed", "focus-completed-badge"));
        }

        VBox info = new VBox(2, subject, title, badges);
        HBox.setHgrow(info, javafx.scene.layout.Priority.ALWAYS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        Button edit = new Button("Edit");
        edit.getStyleClass().add("quiet-button");
        edit.setOnAction(event -> beginEdit(session));

        Button start = new Button(active
                ? (timer != null && timer.isRunning() ? "Pause" : "Resume")
                : "Start");
        start.getStyleClass().add("quiet-button");
        start.setDisable(session.completed());
        start.setOnAction(event -> onSessionStart(session));

        Button done = new Button("Done");
        done.getStyleClass().add("quiet-button");
        done.setDisable(session.completed());
        done.setOnAction(event -> completeSession(session));

        Button remove = new Button("x");
        remove.getStyleClass().add("attachment-remove");
        remove.setOnAction(event -> {
            mutate(plannerStatus, () -> {
                focusService.removeSession(session.id());
                return null;
            }, ignored -> {
                if (activeSession != null && activeSession.id().equals(session.id())) {
                    stopActiveSession();
                }
                if (editingSession != null && editingSession.id().equals(session.id())) {
                    resetSessionForm();
                    setStatus(sessionStatus,
                            "Editing cancelled because the session was removed.", false);
                }
                setStatus(plannerStatus, "Session removed.", false);
            });
        });

        row.getChildren().addAll(time, info, spacer, edit, start, done, remove);
        return row;
    }

    private static Label badge(String text, String styleClass) {
        Label badge = new Label(text);
        badge.getStyleClass().addAll("focus-badge", styleClass);
        return badge;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted-copy");
        label.setWrapText(true);
        return label;
    }

    private void updateSummary() {
        FocusPlan plan = focusService.currentPlan();
        sessionCount.setText(plan.sessions().size() + " sessions");
        if (plan.sessions().isEmpty()) {
            planSummary.setText("No sessions planned yet.");
        } else {
            planSummary.setText(plan.sessions().size() + " sessions - "
                    + formatDuration(plan.totalMinutes()) + " planned - "
                    + plan.subjectCount() + " subjects - "
                    + plan.completedCount() + " completed");
        }
        if (plan.warnings().isEmpty()) {
            warningsLabel.setManaged(false);
            warningsLabel.setVisible(false);
        } else {
            warningsLabel.setText(String.join("\n", plan.warnings()));
            warningsLabel.setManaged(true);
            warningsLabel.setVisible(true);
        }
        clearPlanButton.setDisable(plan.sessions().isEmpty());
    }

    private void onSessionStart(FocusSession session) {
        if (focusService == null || session.completed()) {
            return;
        }
        if (activeSession != null && activeSession.id().equals(session.id())) {
            toggleTimer();
            return;
        }
        if (activeSession != null) {
            timerHint.setText("Finish or pause the current session first.");
            return;
        }

        activeSession = session;
        completionFailed = false;
        timer = new StudyTimer(session.durationMinutes() * 60,
                Math.max(60, session.durationMinutes() * 60));
        timer.start();
        paused = false;
        if (mascotStateService != null) {
            mascotStateService.setState(MascotState.STUDYING);
        }
        timerHint.setText(session.type() == FocusType.DEEP
                ? "Deep work in progress. Puppy is focusing with you."
                : "Quick focus in progress. One small block at a time.");
        refresh();
    }

    @FXML
    private void onToggleSessionTimer() {
        if (activeSession != null) {
            toggleTimer();
        }
    }

    private void toggleTimer() {
        if (timer == null) {
            return;
        }
        if (timer.isRunning()) {
            timer.pause();
            paused = true;
            if (mascotStateService != null) {
                mascotStateService.resetToIdle();
            }
            timerHint.setText("Paused. Resume when you are ready.");
        } else if (!timer.isFinished()) {
            timer.start();
            paused = false;
            if (mascotStateService != null) {
                mascotStateService.setState(MascotState.STUDYING);
            }
            timerHint.setText("Focusing. Puppy is right here with you.");
        }
        updateTimerCard();
    }

    @FXML
    private void onFinishSession() {
        if (activeSession != null) {
            finishActiveSession();
        }
    }

    private void completeSession(FocusSession session) {
        if (activeSession != null && activeSession.id().equals(session.id())) {
            finishActiveSession();
            return;
        }
        mutate(plannerStatus, () -> focusService.completeSession(session.id()), completed ->
                setStatus(plannerStatus, "Session marked complete. Nice work!", false));
    }

    private void finishActiveSession() {
        if (activeSession == null || saving || importing || closed) {
            return;
        }
        FocusSession finished = activeSession;
        if (timer != null && timer.isRunning()) {
            timer.pause();
            paused = true;
        }
        mutate(plannerStatus, () -> focusService.completeSession(finished.id()), completed -> {
            activeSession = null;
            timer = null;
            paused = false;
            if (mascotStateService != null) {
                mascotStateService.setState(MascotState.HAPPY);
                happyReaction.playFromStart();
            }
            timerHint.setText("Session complete. Take a short break.");
            setStatus(plannerStatus, finished.subject() + " session complete. Well done!", false);
        }, error -> {
            completionFailed = true;
            timerHint.setText("Completion was not saved. Press Finish to retry.");
            if (mascotStateService != null) {
                mascotStateService.resetToIdle();
            }
        });
    }

    private void stopActiveSession() {
        if (timer != null && timer.isRunning()) {
            timer.pause();
        }
        activeSession = null;
        timer = null;
        paused = false;
        if (mascotStateService != null) {
            mascotStateService.resetToIdle();
        }
        updateTimerCard();
    }

    private void tick() {
        if (activeSession == null || timer == null) {
            return;
        }
        if (timer.isFinished()) {
            if (!completionFailed) {
                finishActiveSession();
            }
            return;
        }
        updateTimerCard();
    }

    private void updateTimerCard() {
        if (timerTitle == null) {
            return;
        }
        if (activeSession == null || timer == null) {
            timerTitle.setText("No session running");
            timerMeta.setText("Pick a session on the right and press Start.");
            timerTime.setText("--:--");
            timerToggle.setText("Start");
            timerToggle.setDisable(true);
            timerFinish.setDisable(true);
            return;
        }
        timerTitle.setText(activeSession.subject());
        timerMeta.setText(activeSession.title() + " - " + activeSession.type().label());
        int seconds = Math.max(0, timer.getRemainingSeconds());
        timerTime.setText("%02d:%02d".formatted(seconds / 60, seconds % 60));
        timerToggle.setText(timer.isRunning() ? "Pause" : "Resume");
        timerToggle.setDisable(saving || importing || timer.isFinished());
        timerFinish.setDisable(saving || importing);
    }

    @FXML
    private void onImportFiles() {
        if (focusService == null || importing || saving || closed) {
            return;
        }
        if (executor == null) {
            setStatus(importStatus, "Import is not available right now.", true);
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import timetable or subject file");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(
                        "Timetable and subject files", "*.pdf", "*.docx", "*.doc", "*.txt", "*.md"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        var window = focusRoot.getScene() == null ? null : focusRoot.getScene().getWindow();
        java.util.List<File> selected = chooser.showOpenMultipleDialog(window);
        if (selected == null || selected.isEmpty()) {
            return;
        }
        if (selected.size() > 5) {
            setStatus(importStatus, "Choose up to 5 files at a time.", true);
            return;
        }

        java.util.List<Path> paths = selected.stream()
                .map(file -> file.toPath().toAbsolutePath().normalize())
                .toList();
        importing = true;
        updateBusyState();
        setStatus(importStatus,
                "Reading " + paths.size() + " file(s) and asking the local model...", false);

        int generation = ++importGeneration;
        try {
            inFlight = executor.submit(() -> {
                try {
                    FocusPlan plan = focusService.importFromFiles(paths, null);
                    Platform.runLater(() -> {
                        if (generation != importGeneration) {
                            return;
                        }
                        importing = false;
                        updateBusyState();
                        refresh();
                        String message = plan.sessions().isEmpty()
                                ? "I read the files, but no sessions could be placed."
                                : "Plan ready: " + plan.sessions().size()
                                + " sessions from your files.";
                        setStatus(importStatus, message,
                                plan.sessions().isEmpty() && !plan.warnings().isEmpty());
                    });
                } catch (FocusPlanningException e) {
                    Platform.runLater(() -> {
                        if (generation != importGeneration) {
                            return;
                        }
                        importing = false;
                        updateBusyState();
                        setStatus(importStatus, e.getMessage(), true);
                    });
                } catch (RuntimeException e) {
                    Platform.runLater(() -> {
                        if (generation != importGeneration) {
                            return;
                        }
                        importing = false;
                        updateBusyState();
                        setStatus(importStatus,
                                "Import could not be saved: " + errorMessage(e), true);
                    });
                } finally {
                    Thread.interrupted();
                }
            });
        } catch (RejectedExecutionException e) {
            importing = false;
            updateBusyState();
            setStatus(importStatus, "The application is shutting down.", true);
        }
    }

    private void updateBusyState() {
        boolean busy = importing || saving;
        importButton.setDisable(busy);
        generatePlanButton.setDisable(busy);
        plannerTabs.setDisable(busy);
        classList.setDisable(busy);
        taskList.setDisable(busy);
        sessionsBox.setDisable(busy);
        availableFrom.setDisable(busy);
        availableTo.setDisable(busy);
        clearPlanButton.setDisable(busy || focusService.sessions().isEmpty());
        timerFinish.setDisable(busy || activeSession == null);
    }

    @FunctionalInterface
    private interface Mutation<T> {
        T run() throws Exception;
    }

    private <T> void mutate(Label status, Mutation<T> work, Consumer<T> success) {
        mutate(status, work, success, error -> { });
    }

    private <T> void mutate(Label status, Mutation<T> work, Consumer<T> success,
                            Consumer<Exception> failure) {
        if (saving || importing || closed) {
            return;
        }
        saving = true;
        updateBusyState();
        setStatus(status, "Saving...", false);
        try {
            executor.submit(() -> {
                try {
                    T result = work.run();
                    Platform.runLater(() -> {
                        saving = false;
                        if (closed) {
                            return;
                        }
                        success.accept(result);
                        refresh();
                        updateBusyState();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        saving = false;
                        if (closed) {
                            return;
                        }
                        setStatus(status, "Changes were not saved: " + errorMessage(e), true);
                        failure.accept(e);
                        updateBusyState();
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            saving = false;
            setStatus(status, "Changes were not saved: the application is shutting down.", true);
            failure.accept(e);
            updateBusyState();
        }
    }

    private static String errorMessage(Exception error) {
        return error.getMessage() == null ? "Storage is unavailable. Try again."
                : error.getMessage();
    }

    @FXML
    private void onGeneratePlan() {
        if (focusService == null) {
            return;
        }
        if (focusService.tasks().isEmpty()) {
            setStatus(plannerStatus,
                    "Add at least one task, or import a subject file first.", true);
            return;
        }
        mutate(plannerStatus, focusService::generatePlan, plan -> {
            if (plan.sessions().isEmpty()) {
                setStatus(plannerStatus,
                        "No sessions fit. Widen your free time or remove a class.", true);
            } else {
                setStatus(plannerStatus, "Planned " + plan.sessions().size() + " sessions ("
                        + formatDuration(plan.totalMinutes()) + ").", false);
            }
        });
    }

    @FXML
    private void onAddClass() {
        if (focusService == null) {
            return;
        }
        try {
            String subject = requireText(classSubject, "Enter the subject name.");
            DayOfWeek day = classDay.getValue();
            if (day == null) {
                throw new IllegalArgumentException("Choose the weekday.");
            }
            LocalTime start = FocusFormats.parseTime(classStart.getText());
            LocalTime end = FocusFormats.parseTime(classEnd.getText());
            ClassSlot draft = new ClassSlot(subject, day, start, end);
            mutate(plannerStatus, () -> {
                focusService.addClass(draft);
                return null;
            }, ignored -> {
                classSubject.clear();
                classStart.clear();
                classEnd.clear();
                setStatus(plannerStatus, "Class added. Press Generate plan to update sessions.", false);
            });
        } catch (RuntimeException e) {
            setStatus(plannerStatus, e.getMessage(), true);
        }
    }

    @FXML
    private void onAddTask() {
        if (focusService == null) {
            return;
        }
        try {
            String subject = requireText(taskSubject, "Enter the subject name.");
            String title = requireText(taskTitle, "Enter the task name.");
            FocusType type = taskType.getValue() == null ? FocusType.QUICK : taskType.getValue();
            int minutes = FocusFormats.parseMinutes(taskMinutes.getText());
            if (minutes <= 0) {
                throw new IllegalArgumentException("Enter a duration in minutes.");
            }
            LocalDate deadline = taskDeadline.getValue();
            StudyTask draft = new StudyTask(subject, title, type, minutes, deadline, Priority.MEDIUM);
            mutate(plannerStatus, () -> {
                focusService.addTask(draft);
                return null;
            }, ignored -> {
                taskTitle.clear();
                taskMinutes.clear();
                setStatus(plannerStatus, "Task added. Press Generate plan to schedule it.", false);
            });
        } catch (RuntimeException e) {
            setStatus(plannerStatus, e.getMessage(), true);
        }
    }

    @FXML
    private void onSaveSession() {
        if (focusService == null) {
            return;
        }
        try {
            String subject = requireText(sessionSubject, "Enter the subject name.");
            String title = requireText(sessionTitle, "Enter the session name.");
            FocusType type = sessionType.getValue() == null
                    ? FocusType.QUICK : sessionType.getValue();
            LocalDate date = sessionDate.getValue() == null
                    ? LocalDate.now() : sessionDate.getValue();
            LocalTime start = FocusFormats.parseTime(sessionStart.getText());
            int minutes = FocusFormats.parseMinutes(sessionMinutes.getText());
            if (editingSession != null) {
                FocusSession draft = new FocusSession(editingSession.id(), subject, title, type,
                        date, start, minutes, editingSession.priority(),
                        editingSession.completed());
                mutate(sessionStatus, () -> focusService.updateSession(draft), saved -> {
                    resetSessionForm();
                    setStatus(sessionStatus,
                            "Session updated to " + saved.timeRange() + ".", false);
                });
            } else {
                FocusSession session = FocusService.newManualSession(
                        subject, title, type, date, start, minutes);
                mutate(sessionStatus, () -> focusService.addSession(session), saved -> {
                    sessionTitle.clear();
                    sessionStart.clear();
                    sessionMinutes.clear();
                    setStatus(sessionStatus,
                            "Session added at " + saved.timeRange() + ".", false);
                });
            }
        } catch (RuntimeException e) {
            setStatus(sessionStatus, e.getMessage(), true);
        }
    }

    private void beginEdit(FocusSession session) {
        if (session == null) {
            return;
        }
        if (activeSession != null && activeSession.id().equals(session.id())) {
            stopActiveSession();
        }
        editingSession = session;
        sessionSubject.setText(session.subject());
        sessionTitle.setText(session.title());
        sessionType.setValue(session.type());
        sessionDate.setValue(session.date());
        sessionStart.setText(FocusFormats.formatTime(session.start()));
        sessionMinutes.setText(Integer.toString(session.durationMinutes()));
        sessionSaveButton.setText("Save changes");
        cancelEditButton.setManaged(true);
        cancelEditButton.setVisible(true);
        sessionFormHint.setText("Editing " + session.subject() + " - " + session.title()
                + ". Changes must stay inside free time and must not overlap "
                + "a class or another session.");
        setStatus(sessionStatus, "Change the fields, then press Save changes.", false);
        plannerTabs.getSelectionModel().select(sessionTab);
        refresh();
    }

    @FXML
    private void onCancelEdit() {
        resetSessionForm();
        setStatus(sessionStatus, "Editing cancelled. No changes were saved.", false);
    }

    private void resetSessionForm() {
        editingSession = null;
        if (sessionSubject != null) {
            sessionSubject.clear();
            sessionTitle.clear();
            sessionStart.clear();
            sessionMinutes.clear();
            sessionType.setValue(FocusType.QUICK);
            sessionDate.setValue(LocalDate.now());
        }
        if (sessionSaveButton != null) {
            sessionSaveButton.setText("Add session");
            cancelEditButton.setManaged(false);
            cancelEditButton.setVisible(false);
            sessionFormHint.setText("Create a one-off session directly in the planner.");
        }
    }

    @FXML
    private void onApplyAvailability() {
        if (focusService == null) {
            return;
        }
        try {
            FocusPreferences preferences = new FocusPreferences(
                    FocusFormats.parseTime(availableFrom.getValue()),
                    FocusFormats.parseTime(availableTo.getValue()));
            mutate(availabilityStatus, () -> {
                focusService.setPreferences(preferences);
                return null;
            }, ignored -> {
                setStatus(availabilityStatus, "Sessions stay inside "
                        + FocusFormats.formatTime(preferences.earliest()) + " - "
                        + FocusFormats.formatTime(preferences.latest())
                        + " and avoid every class.", false);
                setStatus(plannerStatus, "Free time updated. Generate a plan to apply it.", false);
            });
        } catch (RuntimeException e) {
            setStatus(availabilityStatus, e.getMessage(), true);
        }
    }

    @FXML
    private void onClearPlan() {
        if (focusService == null) {
            return;
        }
        mutate(plannerStatus, () -> {
            focusService.clearSessions();
            return null;
        }, ignored -> {
            stopActiveSession();
            setStatus(plannerStatus, "Sessions cleared. Import or generate again.", false);
        });
    }

    private static String requireText(TextField field, String message) {
        String text = field.getText() == null ? "" : field.getText().strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private static java.util.List<String> halfHourOptions() {
        java.util.List<String> options = new ArrayList<>();
        for (int minutes = 5 * 60; minutes <= 23 * 60 + 30; minutes += 30) {
            options.add("%02d:%02d".formatted(minutes / 60, minutes % 60));
        }
        return options;
    }

    private static void setStatus(Label label, String message, boolean error) {
        if (label == null) {
            return;
        }
        label.setText(message == null ? "" : message);
        label.getStyleClass().remove("error-copy");
        if (error) {
            label.getStyleClass().add("error-copy");
        }
        label.setManaged(true);
        label.setVisible(true);
    }

    static String formatDuration(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + "m";
        }
        return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
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

    /** Used by the mascot's inactivity rule; a running focus timer keeps it awake. */
    public boolean isTimerRunning() {
        return timer != null && timer.isRunning();
    }

    /** Stops background work and leaves an active session paused for the next open. */
    public void close() {
        closed = true;
        importGeneration++;
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
        if (ticker != null) {
            ticker.stop();
        }
        if (happyReaction != null) {
            happyReaction.stop();
        }
        if (timer != null && timer.isRunning()) {
            timer.pause();
            paused = true;
        }
        if (mascotStateService != null) {
            mascotStateService.resetToIdle();
        }
    }
}
