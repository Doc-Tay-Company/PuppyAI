package com.puppy.service;

import com.puppy.document.DocumentExtractionException;
import com.puppy.document.DocumentTextExtractor;
import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.DocumentAttachment;
import com.puppy.model.FocusEditAction;
import com.puppy.model.FocusEditResult;
import com.puppy.model.FocusExtraction;
import com.puppy.model.FocusPlan;
import com.puppy.model.FocusPlannerInput;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.FocusState;
import com.puppy.model.StudyTask;
import com.puppy.repository.FocusRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Application service for the Focus planner. It owns the in-memory classes,
 * tasks, preferences and current plan, and is the only place the UI asks for
 * scheduling changes.
 *
 * <p>Construction with a repository and all durable mutations must run on a
 * worker thread. Getters expose only committed state.</p>
 */
public final class FocusService {

    private static final int MAX_IMPORTED_FILES = 5;
    private static final int REVIEW_TASK_MINUTES = 50;
    private static final int EXTRA_TIME_MINIMUM_MINUTES = 30;
    private static final double EXTRA_TIME_FACTOR = 1.5;
    private static final int MAX_TASK_MINUTES = 600;

    private final FocusPlanExtractor extractor;
    private final DocumentTextExtractor documentTextExtractor;
    private final FocusPlanner planner = new FocusPlanner();
    private final Clock clock;
    private final FocusRepository repository;
    private final Map<ClassSlot, String> classIds = new LinkedHashMap<>();
    private final Map<StudyTask, String> taskIds = new LinkedHashMap<>();

    private final List<ClassSlot> classes = new ArrayList<>();
    private final List<StudyTask> tasks = new ArrayList<>();
    private final List<Consumer<FocusPlan>> planChangeListeners = new CopyOnWriteArrayList<>();
    private final Set<String> prioritizedSubjects = new LinkedHashSet<>();
    private FocusPreferences preferences = FocusPreferences.defaults();
    private FocusPlan plan;
    private boolean preferencesConfigured;
    private boolean planEdited;
    private UndoSnapshot undoSnapshot;

    /**
     * The plan and preferences before the last hand-made or AI edit. Undo is
     * intentionally transient: it covers the current session, not a restart.
     */
    private record UndoSnapshot(FocusPlan plan, FocusPreferences preferences,
                                boolean preferencesConfigured, boolean planEdited) {
    }

    public FocusService(FocusPlanExtractor extractor, DocumentTextExtractor documentTextExtractor) {
        this(extractor, documentTextExtractor, Clock.systemDefaultZone());
    }

    public FocusService(FocusPlanExtractor extractor,
                        DocumentTextExtractor documentTextExtractor,
                        Clock clock) {
        this(extractor, documentTextExtractor, clock, null);
    }

    public FocusService(FocusPlanExtractor extractor,
                        DocumentTextExtractor documentTextExtractor,
                        FocusRepository repository) {
        this(extractor, documentTextExtractor, Clock.systemDefaultZone(), repository);
    }

    public FocusService(FocusPlanExtractor extractor,
                        DocumentTextExtractor documentTextExtractor,
                        Clock clock, FocusRepository repository) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.documentTextExtractor = Objects.requireNonNull(
                documentTextExtractor, "documentTextExtractor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.repository = repository;
        this.plan = FocusPlan.empty(today(),
                "Import your timetable or add tasks, then press Generate plan.");
        if (repository != null) {
            repository.load().ifPresent(this::restore);
        }
    }

    /**
     * Extracts text from each file, asks the model for structured planner data,
     * merges it with what is already here and rebuilds the plan.
     *
     * @param override when non-null, every task is forced to this focus type
     */
    public FocusPlan importFromFiles(List<Path> files, FocusType override)
            throws FocusPlanningException {
        if (files == null || files.isEmpty()) {
            throw new FocusPlanningException("Choose at least one timetable or subject file.");
        }
        if (files.size() > MAX_IMPORTED_FILES) {
            throw new FocusPlanningException(
                    "Attach up to " + MAX_IMPORTED_FILES + " files at a time.");
        }
        List<DocumentAttachment> documents = new ArrayList<>();
        for (Path file : files) {
            try {
                documents.add(documentTextExtractor.extract(file));
            } catch (DocumentExtractionException e) {
                throw new FocusPlanningException(e.getMessage(), e);
            }
        }
        return importDocuments(documents, override);
    }

    /** Same as {@link #importFromFiles(List, FocusType)} for files already read by chat. */
    public FocusPlan importDocuments(List<DocumentAttachment> documents, FocusType override)
            throws FocusPlanningException {
        if (documents == null || documents.isEmpty()) {
            throw new FocusPlanningException(
                    "Attach your timetable or subject file first, then try again.");
        }

        FocusExtraction extraction = extractor.extract(List.copyOf(documents));
        List<String> notes = new ArrayList<>(extraction.notes());
        List<String> moreTimeSubjects = extraction.input().moreTimeSubjects();

        synchronized (this) {
            FocusPlan imported = mutate(() -> {
                mergeClasses(extraction.input().classes());
                mergeTasks(extraction.input().tasks());
                if (moreTimeSubjects != null && !moreTimeSubjects.isEmpty()) {
                    prioritizedSubjects.addAll(moreTimeSubjects);
                }
                addMissingReviewTasks(moreTimeSubjects, notes);
                applyExtraTime(moreTimeSubjects, notes);
                if (override != null) {
                    for (StudyTask task : tasks) {
                        taskIds.putIfAbsent(task.withType(override),
                                taskIds.getOrDefault(task, UUID.randomUUID().toString()));
                    }
                    tasks.replaceAll(task -> task.withType(override));
                }
                plan = buildPlan(notes);
                planEdited = false;
                return plan;
            });
            undoSnapshot = null;
            return imported;
        }
    }

    /**
     * Imports planner documents and lets the model decide each task's focus
     * type. Deep-focus signals such as importance, difficulty or low confidence
     * are handled in the extraction prompt.
     */
    public FocusPlan importDocuments(List<DocumentAttachment> documents)
            throws FocusPlanningException {
        return importDocuments(documents, null);
    }

    /** Rebuilds the plan from the classes, tasks and preferences already loaded. */
    public synchronized FocusPlan generatePlan() {
        FocusPlan generated = mutate(() -> {
            plan = buildPlan(List.of());
            planEdited = false;
            return plan;
        });
        undoSnapshot = null;
        return generated;
    }

    public synchronized FocusPlan currentPlan() {
        return plan;
    }

    public synchronized List<ClassSlot> classes() {
        return List.copyOf(classes);
    }

    public synchronized List<StudyTask> tasks() {
        return List.copyOf(tasks);
    }

    public synchronized List<FocusSession> sessions() {
        return plan.sessions();
    }

    public synchronized FocusPreferences preferences() {
        return preferences;
    }

    /**
     * True once the user has chosen a free-time window. Chat can then reuse it
     * instead of asking for a window before every plan.
     */
    public synchronized boolean preferencesConfigured() {
        return preferencesConfigured;
    }

    /** True when sessions were changed by hand or by the AI plan editor. */
    public synchronized boolean hasPlanEdits() {
        return planEdited;
    }

    /** True when a session edit can still be undone in this session. */
    public synchronized boolean canUndo() {
        return undoSnapshot != null;
    }

    public synchronized void setPreferences(FocusPreferences newPreferences) {
        mutate(() -> {
            preferences = Objects.requireNonNull(newPreferences, "newPreferences");
            preferencesConfigured = true;
            return null;
        });
        // A settings change breaks the "last plan edit" chain; undoing it later
        // must not silently bring back an older window.
        undoSnapshot = null;
    }

    public synchronized void addClass(ClassSlot classSlot) {
        Objects.requireNonNull(classSlot, "classSlot");
        mutate(() -> {
            if (!classes.contains(classSlot)) {
                classes.add(classSlot);
            }
            return null;
        });
    }

    public synchronized void removeClass(ClassSlot classSlot) {
        mutate(() -> classes.remove(classSlot));
    }

    public synchronized void addTask(StudyTask task) {
        Objects.requireNonNull(task, "task");
        mutate(() -> {
            if (!tasks.contains(task)) {
                tasks.add(task);
            }
            return null;
        });
    }

    public synchronized void removeTask(StudyTask task) {
        mutate(() -> tasks.remove(task));
    }

    /**
     * Adds a hand-made session after checking every constraint the planner
     * enforces: no class overlap, no session overlap and stay inside free time.
     */
    public synchronized FocusSession addSession(FocusSession session) throws FocusPlanningException {
        Objects.requireNonNull(session, "session");
        validatePlacement(session, null);
        List<FocusSession> updated = new ArrayList<>(plan.sessions());
        updated.add(session);
        updated.sort(sessionOrder());
        UndoSnapshot undo = captureUndo();
        mutate(() -> {
            plan = new FocusPlan(updated, plan.warnings(), plan.generatedOn());
            planEdited = true;
            return null;
        });
        undoSnapshot = undo;
        return session;
    }

    /**
     * Applies edited fields to an existing session. The edited session is
     * excluded from overlap checks, its completion flag is preserved and the
     * session list is sorted again by date and start time.
     */
    public synchronized FocusSession updateSession(FocusSession updated)
            throws FocusPlanningException {
        Objects.requireNonNull(updated, "updated");
        List<FocusSession> sessions = new ArrayList<>(plan.sessions());
        int index = -1;
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id().equals(updated.id())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new FocusPlanningException("That session is no longer in the plan.");
        }
        validatePlacement(updated, updated.id());
        FocusSession existing = sessions.get(index);
        FocusSession merged = updated.completed() || existing.completed()
                ? updated.asCompleted()
                : updated;
        sessions.set(index, merged);
        sessions.sort(sessionOrder());
        UndoSnapshot undo = captureUndo();
        mutate(() -> {
            plan = new FocusPlan(sessions, plan.warnings(), plan.generatedOn());
            planEdited = true;
            return null;
        });
        undoSnapshot = undo;
        return merged;
    }

    /**
     * Updates one session, optionally applying the edited subject, type, start
     * time, duration and priority to every session of the same subject. Each
     * session keeps its own date, title and completion state. All sessions are
     * validated first, so the batch is all-or-nothing.
     *
     * @return the saved sessions in plan order
     */
    public synchronized List<FocusSession> updateSessions(FocusSession updated,
                                                          boolean applyToAllSubjects)
            throws FocusPlanningException {
        if (!applyToAllSubjects) {
            return List.of(updateSession(updated));
        }
        Objects.requireNonNull(updated, "updated");
        List<FocusSession> sessions = new ArrayList<>(plan.sessions());
        int index = -1;
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id().equals(updated.id())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new FocusPlanningException("That session is no longer in the plan.");
        }

        FocusSession original = sessions.get(index);
        String subjectKey = FocusFormats.comparisonKey(original.subject());
        List<FocusSession> candidates = new ArrayList<>(sessions.size());
        java.util.Set<String> changed = new java.util.LinkedHashSet<>();
        for (FocusSession session : sessions) {
            boolean sameSubject = FocusFormats.comparisonKey(session.subject())
                    .equals(subjectKey);
            boolean target = session.id().equals(original.id());
            if (!sameSubject || (session.date().isBefore(today()) && !target)) {
                // Leave other past sessions untouched rather than failing the
                // whole batch because history can no longer be moved.
                candidates.add(session);
                continue;
            }
            candidates.add(new FocusSession(
                    session.id(),
                    updated.subject(),
                    target ? updated.title() : session.title(),
                    updated.type(),
                    session.date(),
                    updated.start(),
                    updated.durationMinutes(),
                    updated.priority(),
                    target ? updated.completed() || session.completed() : session.completed()));
            changed.add(session.id());
        }
        for (FocusSession candidate : candidates) {
            if (changed.contains(candidate.id())) {
                validatePlacement(candidate, candidate.id(), candidates);
            }
        }
        candidates.sort(sessionOrder());
        UndoSnapshot undo = captureUndo();
        mutate(() -> {
            plan = new FocusPlan(candidates, plan.warnings(), plan.generatedOn());
            planEdited = true;
            return null;
        });
        undoSnapshot = undo;
        return candidates.stream()
                .filter(session -> changed.contains(session.id()))
                .toList();
    }

    public synchronized FocusSession completeSession(String id) throws FocusPlanningException {
        List<FocusSession> updated = new ArrayList<>(plan.sessions());
        for (int i = 0; i < updated.size(); i++) {
            FocusSession session = updated.get(i);
            if (session.id().equals(id)) {
                FocusSession completed = session.asCompleted();
                updated.set(i, completed);
                mutate(() -> plan = new FocusPlan(updated, plan.warnings(), plan.generatedOn()));
                undoSnapshot = null;
                return completed;
            }
        }
        throw new FocusPlanningException("That session is no longer in the plan.");
    }

    /** Removes one session and makes that removal undoable. */
    public synchronized void removeSession(String id) {
        if (plan.sessions().stream().noneMatch(session -> session.id().equals(id))) {
            return;
        }
        UndoSnapshot undo = captureUndo();
        mutate(() -> {
            plan = new FocusPlan(
                    plan.sessions().stream()
                            .filter(session -> !session.id().equals(id)).toList(),
                    plan.warnings(),
                    plan.generatedOn());
            planEdited = true;
            return null;
        });
        undoSnapshot = undo;
    }

    /**
     * Empties the plan. This resets the edit flags as well, so a later
     * generate/import does not ask about changes the user already discarded.
     */
    public synchronized void clearSessions() {
        mutate(() -> {
            plan = new FocusPlan(List.of(), List.of("The session list is empty. "
                    + "Press Generate plan to rebuild it."), plan.generatedOn());
            planEdited = false;
            return null;
        });
        undoSnapshot = null;
    }

    /**
     * Applies a batch of edit actions from the AI plan editor to the current
     * plan. Every action is validated first and the whole batch is committed
     * atomically, so one invalid action leaves the plan untouched.
     *
     * @return the saved sessions in plan order
     */
    public synchronized List<FocusSession> applyEdits(List<FocusEditAction> actions)
            throws FocusPlanningException {
        Objects.requireNonNull(actions, "actions");
        if (actions.isEmpty()) {
            return plan.sessions();
        }

        List<FocusSession> originalSessions = plan.sessions();
        for (FocusEditAction action : actions) {
            if (FocusEditAction.targetsSession(action.type())) {
                int index = action.sessionIndex() - 1;
                if (index < 0 || index >= originalSessions.size()) {
                    throw new FocusPlanningException(
                            "Session " + action.sessionIndex()
                                    + " is not in the plan (the plan currently has "
                                    + originalSessions.size() + " session"
                                    + (originalSessions.size() == 1 ? "" : "s") + ").");
                }
            }
        }

        // Resolve the final window first so ADD defaults and every placement
        // check use the window the request ends up with.
        FocusPreferences targetPreferences = preferences;
        boolean windowChanged = false;
        for (FocusEditAction action : actions) {
            if (action.type() == FocusEditAction.Type.SET_WINDOW) {
                targetPreferences = new FocusPreferences(
                        action.earliest(), action.latest(), targetPreferences.planUntil());
                windowChanged = true;
            }
        }

        Map<String, FocusSession> sessionMap = new LinkedHashMap<>();
        for (FocusSession session : originalSessions) {
            sessionMap.put(session.id(), session);
        }

        List<FocusSession> addedSessions = new ArrayList<>();
        Set<String> changedIds = new LinkedHashSet<>();

        for (FocusEditAction action : actions) {
            switch (action.type()) {
                case SET_WINDOW -> {
                    // Applied to the whole plan through targetPreferences.
                }
                case UPDATE, MOVE, RESIZE, CHANGE_SUBJECT -> {
                    String targetId = originalSessions.get(action.sessionIndex() - 1).id();
                    FocusSession existing = sessionMap.get(targetId);
                    if (existing == null) {
                        throw new FocusPlanningException(
                                "Session " + action.sessionIndex()
                                        + " was already removed by another edit.");
                    }
                    sessionMap.put(targetId, patch(existing, action));
                    changedIds.add(targetId);
                }
                case REMOVE -> {
                    String targetId = originalSessions.get(action.sessionIndex() - 1).id();
                    sessionMap.remove(targetId);
                    changedIds.remove(targetId);
                }
                case ADD -> {
                    if (action.subject() == null || action.subject().isBlank()) {
                        throw new FocusPlanningException("A new session requires a subject.");
                    }
                    if (action.title() == null || action.title().isBlank()) {
                        throw new FocusPlanningException("A new session requires a title.");
                    }
                    FocusSession newSession = new FocusSession(
                            UUID.randomUUID().toString(),
                            action.subject().strip(),
                            action.title().strip(),
                            action.focusType() != null ? action.focusType() : FocusType.QUICK,
                            action.date() != null ? action.date() : today(),
                            action.start() != null ? action.start() : targetPreferences.earliest(),
                            action.durationMinutes() != null && action.durationMinutes() > 0
                                    ? action.durationMinutes() : 30,
                            action.priority() != null ? action.priority() : Priority.MEDIUM,
                            false);
                    addedSessions.add(newSession);
                    changedIds.add(newSession.id());
                }
            }
        }

        List<FocusSession> currentSessions = new ArrayList<>(sessionMap.values());
        currentSessions.addAll(addedSessions);

        for (FocusSession session : currentSessions) {
            if (changedIds.contains(session.id())) {
                validatePlacement(session, session.id(), currentSessions, targetPreferences);
            } else if (windowChanged && !session.date().isBefore(today())) {
                // History stays as it is, but every future session must still
                // fit the window the user asked for.
                validateWindow(session, targetPreferences);
            }
        }

        currentSessions.sort(sessionOrder());
        FocusPreferences committedPreferences = targetPreferences;
        boolean windowWasChanged = windowChanged;
        UndoSnapshot undo = captureUndo();
        mutate(() -> {
            preferences = committedPreferences;
            if (windowWasChanged) {
                preferencesConfigured = true;
            }
            plan = new FocusPlan(currentSessions, plan.warnings(), plan.generatedOn());
            planEdited = true;
            return null;
        });
        undoSnapshot = undo;
        return plan.sessions();
    }

    /**
     * Compatibility entry point for callers written against the original
     * UPDATE/REMOVE/ADD action set.
     */
    public synchronized List<FocusSession> applyEditActions(List<FocusEditAction> actions)
            throws FocusPlanningException {
        return applyEdits(actions);
    }

    /** Reverts the last hand-made or AI plan edit. Returns false when none exists. */
    public synchronized boolean undoLastEdit() {
        UndoSnapshot undo = undoSnapshot;
        if (undo == null) {
            return false;
        }
        mutate(() -> {
            plan = undo.plan();
            preferences = undo.preferences();
            preferencesConfigured = undo.preferencesConfigured();
            planEdited = undo.planEdited();
            return null;
        });
        undoSnapshot = null;
        return true;
    }

    private UndoSnapshot captureUndo() {
        return new UndoSnapshot(plan, preferences, preferencesConfigured, planEdited);
    }

    /** Builds the patched session for a partial edit, keeping omitted fields. */
    private static FocusSession patch(FocusSession existing, FocusEditAction action)
            throws FocusPlanningException {
        String subject = action.subject() != null && !action.subject().isBlank()
                ? action.subject().strip() : existing.subject();
        String title = action.title() != null && !action.title().isBlank()
                ? action.title().strip() : existing.title();
        int duration = action.durationMinutes() != null
                ? action.durationMinutes() : existing.durationMinutes();
        LocalTime start = action.start() != null ? action.start() : existing.start();
        LocalDate date = action.date() != null ? action.date() : existing.date();
        if (duration <= 0) {
            throw new FocusPlanningException("A session must be longer than zero minutes.");
        }
        return new FocusSession(
                existing.id(),
                subject,
                title,
                action.focusType() != null ? action.focusType() : existing.type(),
                date,
                start,
                duration,
                action.priority() != null ? action.priority() : existing.priority(),
                existing.completed());
    }

    public void addPlanChangeListener(Consumer<FocusPlan> listener) {
        if (listener != null) {
            planChangeListeners.add(listener);
        }
    }

    public void removePlanChangeListener(Consumer<FocusPlan> listener) {
        planChangeListeners.remove(listener);
    }

    private void notifyPlanChange(FocusPlan newPlan) {
        for (Consumer<FocusPlan> listener : planChangeListeners) {
            try {
                listener.accept(newPlan);
            } catch (Exception ignored) {
            }
        }
    }

    private <T> T mutate(Supplier<T> mutation) {
        FocusState before = snapshot();
        try {
            T result = mutation.get();
            FocusState after = snapshot();
            if (repository != null) {
                repository.save(after);
            }
            notifyPlanChange(after.plan());
            return result;
        } catch (RuntimeException e) {
            restore(before);
            throw e;
        }
    }

    private FocusState snapshot() {
        classIds.keySet().retainAll(classes);
        taskIds.keySet().retainAll(tasks);
        return new FocusState(classes.stream().map(value -> new FocusState.ClassEntry(
                classIds.computeIfAbsent(value, key -> UUID.randomUUID().toString()), value)).toList(),
                tasks.stream().map(value -> new FocusState.TaskEntry(
                        taskIds.computeIfAbsent(value, key -> UUID.randomUUID().toString()), value)).toList(),
                preferences, plan, preferencesConfigured, planEdited);
    }

    private void restore(FocusState state) {
        classes.clear();
        tasks.clear();
        classIds.clear();
        taskIds.clear();
        state.classes().forEach(entry -> {
            classes.add(entry.value());
            classIds.put(entry.value(), entry.id());
        });
        state.tasks().forEach(entry -> {
            tasks.add(entry.value());
            taskIds.put(entry.value(), entry.id());
        });
        preferences = state.preferences();
        plan = state.plan();
        // States saved before the flags existed carry no marker. A window that
        // differs from the default was clearly chosen by the user, so reuse it
        // instead of asking again.
        preferencesConfigured = state.preferencesConfigured()
                || !state.preferences().equals(FocusPreferences.defaults());
        planEdited = state.planEdited();
    }

    /** True when a class or a scheduled session already covers this time. */
    public synchronized boolean isOccupied(LocalDate date, java.time.LocalTime start,
                                           java.time.LocalTime end) {
        for (ClassSlot classSlot : classes) {
            if (classSlot.overlaps(date.getDayOfWeek(), start, end)) {
                return true;
            }
        }
        for (FocusSession session : plan.sessions()) {
            if (session.date().equals(date)
                    && session.start().isBefore(end)
                    && start.isBefore(session.end())) {
                return true;
            }
        }
        return false;
    }

    public synchronized void prioritizeSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        prioritizedSubjects.add(subject.strip());
        mutate(() -> {
            String key = FocusFormats.comparisonKey(subject);
            List<StudyTask> updated = new ArrayList<>(tasks.size());
            for (StudyTask task : tasks) {
                if (FocusFormats.comparisonKey(task.subject()).equals(key)) {
                    StudyTask boosted = new StudyTask(task.subject(), task.title(), task.type(),
                            task.estimatedMinutes(), task.deadline(), Priority.HIGH);
                    String id = taskIds.remove(task);
                    if (id != null) {
                        taskIds.put(boosted, id);
                    }
                    updated.add(boosted);
                } else {
                    updated.add(task);
                }
            }
            tasks.clear();
            tasks.addAll(updated);
            applyExtraTime(List.of(subject), new ArrayList<>());
            plan = buildPlan(List.of());
            return null;
        });
        undoSnapshot = null;
    }

    public synchronized void deprioritizeSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        String key = FocusFormats.comparisonKey(subject);
        prioritizedSubjects.removeIf(s -> FocusFormats.comparisonKey(s).equals(key));
        mutate(() -> {
            List<StudyTask> updated = new ArrayList<>(tasks.size());
            for (StudyTask task : tasks) {
                if (FocusFormats.comparisonKey(task.subject()).equals(key)) {
                    StudyTask reset = new StudyTask(task.subject(), task.title(), task.type(),
                            task.estimatedMinutes(), task.deadline(), Priority.MEDIUM);
                    String id = taskIds.remove(task);
                    if (id != null) {
                        taskIds.put(reset, id);
                    }
                    updated.add(reset);
                } else {
                    updated.add(task);
                }
            }
            tasks.clear();
            tasks.addAll(updated);
            plan = buildPlan(List.of("Đã hủy ưu tiên môn " + subject + "."));
            return null;
        });
        undoSnapshot = null;
    }

    public synchronized void dropSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        String key = FocusFormats.comparisonKey(subject);
        prioritizedSubjects.removeIf(s -> FocusFormats.comparisonKey(s).equals(key));
        mutate(() -> {
            tasks.removeIf(task -> FocusFormats.comparisonKey(task.subject()).equals(key));
            List<FocusSession> remaining = plan.sessions().stream()
                    .filter(s -> !FocusFormats.comparisonKey(s.subject()).equals(key))
                    .toList();
            plan = new FocusPlan(remaining, plan.warnings(), plan.generatedOn());
            plan = buildPlan(List.of("Đã loại bỏ môn " + subject + " khỏi kế hoạch học tập."));
            return null;
        });
        undoSnapshot = null;
    }

    public synchronized void setPrioritizedSubjects(List<String> subjects) {
        prioritizedSubjects.clear();
        if (subjects != null) {
            for (String s : subjects) {
                if (s != null && !s.isBlank()) {
                    prioritizedSubjects.add(s.strip());
                }
            }
        }
    }

    public synchronized List<String> prioritizedSubjects() {
        return List.copyOf(prioritizedSubjects);
    }

    public synchronized List<String> subjects() {
        Set<String> set = new LinkedHashSet<>();
        tasks.stream().map(StudyTask::subject).filter(Objects::nonNull).forEach(set::add);
        classes.stream().map(ClassSlot::subject).filter(Objects::nonNull).forEach(set::add);
        return List.copyOf(set);
    }

    private FocusPlan buildPlan(List<String> notes) {
        FocusPreferences active = preferences;
        FocusPlan generated = planner.plan(
                new FocusPlannerInput(classes, tasks, List.copyOf(prioritizedSubjects)),
                active,
                LocalDateTime.now(clock));
        if (active.planUntil() != null || active.planFrom() != null) {
            // A plan scope belongs to the request that produced this plan, not to
            // every plan afterwards, so it is consumed here.
            preferences = active.withPlanScope(null, null);
        }
        List<String> warnings = new ArrayList<>(notes);
        warnings.addAll(generated.warnings());
        return new FocusPlan(generated.sessions(), warnings, generated.generatedOn());
    }

    private void mergeClasses(List<ClassSlot> imported) {
        Set<ClassSlot> unique = new LinkedHashSet<>(classes);
        unique.addAll(imported);
        classes.clear();
        classes.addAll(unique);
    }

    /**
     * Merges by normalised subject + title, so importing the same task again
     * updates its duration or deadline instead of creating a duplicate. A
     * replaced task keeps its durable id.
     */
    private void mergeTasks(List<StudyTask> imported) {
        Map<String, StudyTask> merged = new LinkedHashMap<>();
        Map<String, String> ids = new LinkedHashMap<>();
        for (StudyTask task : tasks) {
            String key = FocusFormats.taskKey(task.subject(), task.title());
            ids.putIfAbsent(key, taskIds.get(task));
            merged.put(key, task);
        }
        for (StudyTask task : imported) {
            merged.put(FocusFormats.taskKey(task.subject(), task.title()), task);
        }

        taskIds.clear();
        tasks.clear();
        for (Map.Entry<String, StudyTask> entry : merged.entrySet()) {
            StudyTask task = entry.getValue();
            tasks.add(task);
            String id = ids.get(entry.getKey());
            taskIds.put(task, id == null ? UUID.randomUUID().toString() : id);
        }
    }

    /**
     * Adds a review task for each timetable subject that has no task at all,
     * plus any explicitly emphasised subject the extraction left uncovered.
     * Existing tasks are never discarded to make room for the review.
     */
    private void addMissingReviewTasks(List<String> moreTimeSubjects, List<String> notes) {
        Set<String> subjectsWithTasks = new LinkedHashSet<>();
        for (StudyTask task : tasks) {
            subjectsWithTasks.add(FocusFormats.comparisonKey(task.subject()));
        }

        Set<String> candidates = new LinkedHashSet<>();
        for (ClassSlot classSlot : classes) {
            candidates.add(classSlot.subject());
        }
        candidates.addAll(moreTimeSubjects);

        LocalDate deadline = today().plusDays(FocusPlanner.DEFAULT_HORIZON_DAYS - 1L);
        List<String> added = new ArrayList<>();
        for (String subject : candidates) {
            if (subject == null || subject.isBlank()
                    || !subjectsWithTasks.add(FocusFormats.comparisonKey(subject))) {
                continue;
            }
            tasks.add(new StudyTask(subject, "Review class notes", FocusType.QUICK,
                    REVIEW_TASK_MINUTES, deadline, Priority.MEDIUM));
            added.add(subject);
        }
        if (!added.isEmpty()) {
            notes.add("Added a review session for " + String.join(", ", added)
                    + " so every subject is covered.");
        }
    }

    /**
     * A subject the user wants to spend more time on gets 50% longer tasks,
     * with a minimum bonus of 30 minutes. Other subjects are unchanged.
     */
    private void applyExtraTime(List<String> moreTimeSubjects, List<String> notes) {
        if (moreTimeSubjects == null || moreTimeSubjects.isEmpty()) {
            return;
        }
        Set<String> emphasised = new LinkedHashSet<>();
        for (String subject : moreTimeSubjects) {
            emphasised.add(FocusFormats.comparisonKey(subject));
        }
        if (emphasised.isEmpty()) {
            return;
        }

        List<StudyTask> updated = new ArrayList<>(tasks.size());
        Set<String> matched = new LinkedHashSet<>();
        for (StudyTask task : tasks) {
            if (!emphasised.contains(FocusFormats.comparisonKey(task.subject()))) {
                updated.add(task);
                continue;
            }
            int minutes = extraMinutes(task.estimatedMinutes());
            if (minutes == task.estimatedMinutes()) {
                updated.add(task);
                matched.add(task.subject());
                continue;
            }
            StudyTask longer = new StudyTask(task.subject(), task.title(), task.type(),
                    minutes, task.deadline(), task.priority());
            String id = taskIds.remove(task);
            if (id != null) {
                taskIds.put(longer, id);
            }
            updated.add(longer);
            matched.add(task.subject());
        }
        tasks.clear();
        tasks.addAll(updated);
        if (!matched.isEmpty()) {
            notes.add("Added extra time for " + String.join(", ", matched)
                    + ": each task is 50% longer, or 30 minutes longer if that is more.");
        }
    }

    private static int extraMinutes(int current) {
        int increased = Math.max(
                current + EXTRA_TIME_MINIMUM_MINUTES,
                (int) Math.round(current * EXTRA_TIME_FACTOR));
        return Math.min(MAX_TASK_MINUTES, increased);
    }

    private void validatePlacement(FocusSession session, String ignoredId)
            throws FocusPlanningException {
        validatePlacement(session, ignoredId, plan.sessions(), preferences);
    }

    private void validatePlacement(FocusSession session, String ignoredId,
                                   List<FocusSession> sessionsToCheck)
            throws FocusPlanningException {
        validatePlacement(session, ignoredId, sessionsToCheck, preferences);
    }

    private void validatePlacement(FocusSession session, String ignoredId,
                                   List<FocusSession> sessionsToCheck,
                                   FocusPreferences window)
            throws FocusPlanningException {
        if (session.date().isBefore(today())) {
            throw new FocusPlanningException("That date is in the past.");
        }
        validateWindow(session, window);
        for (ClassSlot classSlot : classes) {
            if (classSlot.overlaps(session.date().getDayOfWeek(),
                    session.start(), session.end())) {
                throw new FocusPlanningException(
                        "That time overlaps your %s class.".formatted(classSlot.subject()));
            }
        }
        for (FocusSession existing : sessionsToCheck) {
            if (existing.id().equals(ignoredId) || !existing.date().equals(session.date())) {
                continue;
            }
            if (session.start().isBefore(existing.end())
                    && existing.start().isBefore(session.end())) {
                throw new FocusPlanningException(
                        "That time overlaps another focus session.");
            }
        }
    }

    private static void validateWindow(FocusSession session, FocusPreferences window)
            throws FocusPlanningException {
        if (session.start().isBefore(window.earliest())
                || session.end().isAfter(window.latest())) {
            throw new FocusPlanningException(
                    "That session is outside your free time (%s - %s).".formatted(
                            FocusFormats.formatTime(window.earliest()),
                            FocusFormats.formatTime(window.latest())));
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static Comparator<FocusSession> sessionOrder() {
        return Comparator.comparing(FocusSession::date)
                .thenComparing(FocusSession::start)
                .thenComparing(FocusSession::subject, String.CASE_INSENSITIVE_ORDER);
    }

    /** Convenience factory used by the Focus panel for a manual session. */
    public static FocusSession newManualSession(String subject, String title, FocusType type,
                                                LocalDate date, java.time.LocalTime start,
                                                int minutes) {
        return new FocusSession(UUID.randomUUID().toString(), subject, title, type,
                date, start, minutes, Priority.MEDIUM, false);
    }
}
