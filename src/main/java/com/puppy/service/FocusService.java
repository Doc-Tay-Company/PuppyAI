package com.puppy.service;

import com.puppy.document.DocumentExtractionException;
import com.puppy.document.DocumentTextExtractor;
import com.puppy.enums.FocusType;
import com.puppy.enums.FocusSessionState;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.DocumentAttachment;
import com.puppy.model.FocusEditAction;
import com.puppy.model.FocusEditResult;
import com.puppy.model.FocusExtraction;
import com.puppy.model.FocusPlan;
import com.puppy.model.FocusPlanDraft;
import com.puppy.model.FocusPlannerInput;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.FocusState;
import com.puppy.model.StudyTask;
import com.puppy.repository.FocusRepository;
import com.puppy.util.SubjectNames;

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
 * Application service for Study. It owns the in-memory classes,
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
                                List<StudyTask> tasks,
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
                Map<String, String> displayNames = subjectDisplayNames();
                mergeClasses(extraction.input().classes(), displayNames);
                mergeTasks(extraction.input().tasks(), displayNames);
                List<String> resolvedMoreTime = resolveSubjects(moreTimeSubjects, displayNames);
                if (!resolvedMoreTime.isEmpty()) {
                    prioritizedSubjects.addAll(resolvedMoreTime);
                }
                addMissingReviewTasks(resolvedMoreTime, notes, displayNames);
                applyExtraTime(resolvedMoreTime, notes);
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
     * Imports planner documents using the extraction defaults. Focus-type
     * classification is hidden from the UI for now; any type stored in an older
     * plan is preserved and still loads.
     */
    public FocusPlan importDocuments(List<DocumentAttachment> documents)
            throws FocusPlanningException {
        return importDocuments(documents, null);
    }

    /**
     * Builds a complete plan without changing memory or SQLite. The returned
     * draft can be refined repeatedly and is persisted only by
     * {@link #confirmDraft(FocusPlanDraft)}.
     */
    public FocusPlanDraft previewDocuments(List<DocumentAttachment> documents,
                                           FocusPreferences requestedPreferences)
            throws FocusPlanningException {
        FocusState base;
        synchronized (this) {
            base = snapshot();
        }
        FocusService sandbox = sandbox(base);
        if (requestedPreferences != null) {
            sandbox.setPreferences(requestedPreferences);
        }
        FocusPlan preview = sandbox.importDocuments(documents);
        return new FocusPlanDraft(base, sandbox.snapshot(),
                "Generated a validated plan with " + preview.sessions().size()
                        + " sessions.");
    }

    /** Applies structured AI edits to a sandbox copy and returns a preview. */
    public FocusPlanDraft previewEdits(List<FocusEditAction> actions, String summary)
            throws FocusPlanningException {
        FocusState base;
        synchronized (this) {
            base = snapshot();
        }
        return previewEdits(new FocusPlanDraft(base, base, ""), actions, summary);
    }

    /** Refines an existing uncommitted preview without touching committed state. */
    public FocusPlanDraft previewEdits(FocusPlanDraft draft,
                                       List<FocusEditAction> actions, String summary)
            throws FocusPlanningException {
        Objects.requireNonNull(draft, "draft");
        FocusService sandbox = sandbox(draft.proposed());
        sandbox.applyEdits(actions);
        return new FocusPlanDraft(draft.base(), sandbox.snapshot(), summary);
    }

    /** Atomically commits an accepted preview if the underlying plan is unchanged. */
    public synchronized FocusPlan confirmDraft(FocusPlanDraft draft)
            throws FocusPlanningException {
        Objects.requireNonNull(draft, "draft");
        if (!snapshot().equals(draft.base())) {
            throw new FocusPlanningException(
                    "The plan changed while this preview was open. Generate the preview again.");
        }
        FocusPlan committed = mutate(() -> {
            restore(draft.proposed());
            return plan;
        });
        undoSnapshot = null;
        return committed;
    }

    private FocusService sandbox(FocusState state) {
        FocusService sandbox = new FocusService(extractor, documentTextExtractor, clock, null);
        sandbox.restore(state);
        return sandbox;
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
            ClassSlot resolved = resolveClass(classSlot, subjectDisplayNames());
            String key = classKey(resolved);
            if (classes.stream().noneMatch(existing -> classKey(existing).equals(key))) {
                classes.add(resolved);
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
            StudyTask resolved = resolveTask(task, subjectDisplayNames());
            String key = FocusFormats.taskKey(resolved.subject(), resolved.title());
            if (tasks.stream().noneMatch(existing -> FocusFormats.taskKey(
                    existing.subject(), existing.title()).equals(key))) {
                tasks.add(resolved);
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
        FocusSession resolved = resolveSessionSubject(session, subjectDisplayNames());
        validatePlacement(resolved, null);
        List<FocusSession> updated = new ArrayList<>(plan.sessions());
        updated.add(resolved);
        updated.sort(sessionOrder());
        UndoSnapshot undo = captureUndo();
        mutate(() -> {
            plan = new FocusPlan(updated, plan.warnings(), plan.generatedOn());
            planEdited = true;
            return null;
        });
        undoSnapshot = undo;
        return resolved;
    }

    /**
     * Applies edited fields to an existing session. The edited session is
     * excluded from overlap checks, its completion flag is preserved and the
     * session list is sorted again by date and start time.
     */
    public synchronized FocusSession updateSession(FocusSession updated)
            throws FocusPlanningException {
        Objects.requireNonNull(updated, "updated");
        updated = resolveSessionSubject(updated, subjectDisplayNames());
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
        FocusSession merged = updated.withState(existing.state());
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
        updated = resolveSessionSubject(updated, subjectDisplayNames());
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
        String subjectKey = FocusFormats.subjectKey(original.subject());
        List<FocusSession> candidates = new ArrayList<>(sessions.size());
        java.util.Set<String> changed = new java.util.LinkedHashSet<>();
        for (FocusSession session : sessions) {
            boolean sameSubject = FocusFormats.subjectKey(session.subject())
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
                    target ? updated.completed() || session.completed() : session.completed(),
                    session.state()));
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
        return setSessionState(id, FocusSessionState.COMPLETED);
    }

    public synchronized FocusSession skipSession(String id) throws FocusPlanningException {
        return setSessionState(id, FocusSessionState.SKIPPED);
    }

    /** Persists a valid lifecycle transition for the Focus timer UI. */
    public synchronized FocusSession setSessionState(String id, FocusSessionState state)
            throws FocusPlanningException {
        Objects.requireNonNull(state, "state");
        List<FocusSession> updated = new ArrayList<>(plan.sessions());
        for (int i = 0; i < updated.size(); i++) {
            FocusSession session = updated.get(i);
            if (session.id().equals(id)) {
                validateStateTransition(session.state(), state);
                FocusSession changed = session.withState(state);
                updated.set(i, changed);
                mutate(() -> plan = new FocusPlan(updated, plan.warnings(), plan.generatedOn()));
                undoSnapshot = null;
                return changed;
            }
        }
        throw new FocusPlanningException("That session is no longer in the plan.");
    }

    private static void validateStateTransition(FocusSessionState from,
                                                FocusSessionState to)
            throws FocusPlanningException {
        if (from == to) {
            return;
        }
        boolean allowed = switch (from) {
            case PENDING -> to == FocusSessionState.ACTIVE
                    || to == FocusSessionState.COMPLETED || to == FocusSessionState.SKIPPED;
            case ACTIVE -> to == FocusSessionState.PAUSED
                    || to == FocusSessionState.COMPLETED || to == FocusSessionState.SKIPPED;
            case PAUSED -> to == FocusSessionState.ACTIVE
                    || to == FocusSessionState.COMPLETED || to == FocusSessionState.SKIPPED;
            case COMPLETED, SKIPPED -> false;
        };
        if (!allowed) {
            throw new FocusPlanningException(
                    "A " + from.label().toLowerCase() + " session cannot become "
                            + to.label().toLowerCase() + ".");
        }
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
                        action.earliest(), action.latest(), targetPreferences.planFrom(),
                        targetPreferences.planUntil(), targetPreferences.breakMinutes(),
                        targetPreferences.maxSessionMinutes());
                windowChanged = true;
            } else if (action.type() == FocusEditAction.Type.SET_BREAK) {
                targetPreferences = targetPreferences.withBreakMinutes(action.durationMinutes());
            } else if (action.type() == FocusEditAction.Type.SET_SESSION_LIMIT) {
                targetPreferences = targetPreferences.withMaxSessionMinutes(action.durationMinutes());
            }
        }

        Map<String, FocusSession> sessionMap = new LinkedHashMap<>();
        for (FocusSession session : originalSessions) {
            sessionMap.put(session.id(), session);
        }
        Map<String, String> displayNames = subjectDisplayNames();

        List<FocusSession> addedSessions = new ArrayList<>();
        Set<String> changedIds = new LinkedHashSet<>();
        List<StudyTask> candidateTasks = new ArrayList<>(tasks);
        boolean tasksChanged = false;

        for (FocusEditAction action : actions) {
            switch (action.type()) {
                case SET_WINDOW, SET_BREAK, SET_SESSION_LIMIT -> {
                    // Applied to the whole plan through targetPreferences.
                }
                case UPDATE, MOVE, RESIZE, CHANGE_SUBJECT, CHANGE_TYPE -> {
                    String targetId = originalSessions.get(action.sessionIndex() - 1).id();
                    FocusSession existing = sessionMap.get(targetId);
                    if (existing == null) {
                        throw new FocusPlanningException(
                                "Session " + action.sessionIndex()
                                        + " was already removed by another edit.");
                    }
                    sessionMap.put(targetId,
                            resolveSessionSubject(patch(existing, action), displayNames));
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
                            resolveSubject(action.subject(), displayNames),
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
                case ADD_TASK -> {
                    String subject = resolveSubject(action.subject(), displayNames);
                    String title = action.title() == null || action.title().isBlank()
                            ? "Review class notes" : action.title().strip();
                    FocusType type = action.focusType() == null
                            ? FocusType.QUICK : action.focusType();
                    int minutes = action.durationMinutes() == null
                            ? type.defaultBlockMinutes() : action.durationMinutes();
                    StudyTask task = new StudyTask(subject, title, type, minutes,
                            action.date(), action.priority() == null
                                    ? Priority.MEDIUM : action.priority());
                    candidateTasks.removeIf(existing -> FocusFormats.taskKey(
                            existing.subject(), existing.title()).equals(
                            FocusFormats.taskKey(subject, title)));
                    candidateTasks.add(task);
                    FocusSession newSession = new FocusSession(
                            UUID.randomUUID().toString(), subject, title, type,
                            action.date() == null ? today() : action.date(),
                            action.start() == null ? targetPreferences.earliest() : action.start(),
                            Math.min(minutes, targetPreferences.maxSessionMinutes()),
                            task.priority(), false);
                    addedSessions.add(newSession);
                    changedIds.add(newSession.id());
                    tasksChanged = true;
                }
                case REMOVE_TASK -> {
                    String subjectKey = FocusFormats.subjectKey(action.subject());
                    String titleKey = action.title() == null || action.title().isBlank()
                            ? null : FocusFormats.comparisonKey(action.title());
                    candidateTasks.removeIf(task -> FocusFormats.subjectKey(task.subject())
                            .equals(subjectKey) && (titleKey == null
                            || FocusFormats.comparisonKey(task.title()).equals(titleKey)));
                    sessionMap.values().removeIf(session -> !session.state().finished()
                            && FocusFormats.subjectKey(session.subject()).equals(subjectKey)
                            && (titleKey == null
                            || FocusFormats.comparisonKey(session.title()).equals(titleKey)));
                    tasksChanged = true;
                }
                case PRIORITIZE_SUBJECT -> {
                    String subjectKey = FocusFormats.subjectKey(action.subject());
                    Priority priority = action.priority() == null
                            ? Priority.HIGH : action.priority();
                    candidateTasks.replaceAll(task -> FocusFormats.subjectKey(task.subject())
                            .equals(subjectKey)
                            ? new StudyTask(task.subject(), task.title(), task.type(),
                                    task.estimatedMinutes(), task.deadline(), priority)
                            : task);
                    sessionMap.replaceAll((id, session) ->
                            FocusFormats.subjectKey(session.subject()).equals(subjectKey)
                                    && !session.state().finished()
                                    ? new FocusSession(session.id(), session.subject(),
                                            session.title(), session.type(), session.date(),
                                            session.start(), session.durationMinutes(), priority,
                                            session.completed(), session.state())
                                    : session);
                    tasksChanged = true;
                }
            }
        }

        List<FocusSession> currentSessions = new ArrayList<>(sessionMap.values());
        currentSessions.addAll(addedSessions);

        for (FocusSession session : currentSessions) {
            if (!session.state().finished()
                    && session.durationMinutes() > targetPreferences.maxSessionMinutes()) {
                throw new FocusPlanningException(
                        "Session '" + session.title() + "' exceeds the "
                                + targetPreferences.maxSessionMinutes() + " minute session limit.");
            }
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
        boolean taskDataChanged = tasksChanged;
        List<StudyTask> committedTasks = List.copyOf(candidateTasks);
        UndoSnapshot undo = captureUndo();
        mutate(() -> {
            preferences = committedPreferences;
            if (windowWasChanged) {
                preferencesConfigured = true;
            }
            if (taskDataChanged) {
                replaceTasks(committedTasks);
            }
            plan = new FocusPlan(currentSessions, plan.warnings(), plan.generatedOn());
            planEdited = true;
            return null;
        });
        undoSnapshot = undo;
        return plan.sessions();
    }

    private void replaceTasks(List<StudyTask> replacements) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (StudyTask task : tasks) {
            ids.put(FocusFormats.taskKey(task.subject(), task.title()), taskIds.get(task));
        }
        tasks.clear();
        taskIds.clear();
        for (StudyTask task : replacements) {
            tasks.add(task);
            String id = ids.get(FocusFormats.taskKey(task.subject(), task.title()));
            taskIds.put(task, id == null ? UUID.randomUUID().toString() : id);
        }
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
            replaceTasks(undo.tasks());
            preferencesConfigured = undo.preferencesConfigured();
            planEdited = undo.planEdited();
            return null;
        });
        undoSnapshot = null;
        return true;
    }

    private UndoSnapshot captureUndo() {
        return new UndoSnapshot(plan, preferences, List.copyOf(tasks),
                preferencesConfigured, planEdited);
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
                existing.completed(), existing.state());
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
        List<FocusSession> restoredSessions = state.plan().sessions().stream()
                .map(session -> session.state() == FocusSessionState.ACTIVE
                        ? session.withState(FocusSessionState.PAUSED) : session)
                .toList();
        plan = new FocusPlan(restoredSessions, state.plan().warnings(), state.plan().generatedOn());
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
        Map<String, String> displayNames = subjectDisplayNames();
        String resolved = resolveSubject(subject, displayNames);
        prioritizedSubjects.add(resolved);
        mutate(() -> {
            String key = FocusFormats.subjectKey(resolved);
            List<StudyTask> updated = new ArrayList<>(tasks.size());
            for (StudyTask task : tasks) {
                if (FocusFormats.subjectKey(task.subject()).equals(key)) {
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
            applyExtraTime(List.of(resolved), new ArrayList<>());
            plan = buildPlan(List.of());
            return null;
        });
        undoSnapshot = null;
    }

    public synchronized void deprioritizeSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        String key = FocusFormats.subjectKey(subject);
        prioritizedSubjects.removeIf(s -> FocusFormats.subjectKey(s).equals(key));
        mutate(() -> {
            List<StudyTask> updated = new ArrayList<>(tasks.size());
            for (StudyTask task : tasks) {
                if (FocusFormats.subjectKey(task.subject()).equals(key)) {
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
        String key = FocusFormats.subjectKey(subject);
        prioritizedSubjects.removeIf(s -> FocusFormats.subjectKey(s).equals(key));
        mutate(() -> {
            tasks.removeIf(task -> FocusFormats.subjectKey(task.subject()).equals(key));
            List<FocusSession> remaining = plan.sessions().stream()
                    .filter(s -> !FocusFormats.subjectKey(s.subject()).equals(key))
                    .toList();
            plan = new FocusPlan(remaining, plan.warnings(), plan.generatedOn());
            plan = buildPlan(List.of("Đã loại bỏ môn " + subject + " khỏi kế hoạch học tập."));
            return null;
        });
        undoSnapshot = null;
    }

    public synchronized void setPrioritizedSubjects(List<String> subjects) {
        prioritizedSubjects.clear();
        Map<String, String> displayNames = subjectDisplayNames();
        if (subjects != null) {
            for (String s : subjects) {
                if (s != null && !s.isBlank()) {
                    prioritizedSubjects.add(resolveSubject(s, displayNames));
                }
            }
        }
    }

    public synchronized List<String> prioritizedSubjects() {
        return List.copyOf(prioritizedSubjects);
    }

    /**
     * One entry per subject, keeping the spelling already used by the plan so
     * prompts and intent matching still recognise legacy Vietnamese names.
     */
    public synchronized List<String> subjects() {
        Map<String, String> unique = new LinkedHashMap<>();
        tasks.stream().map(StudyTask::subject).filter(Objects::nonNull).forEach(subject ->
                unique.putIfAbsent(SubjectNames.key(subject), subject));
        classes.stream().map(ClassSlot::subject).filter(Objects::nonNull).forEach(subject ->
                unique.putIfAbsent(SubjectNames.key(subject), subject));
        return List.copyOf(unique.values());
    }

    private FocusPlan buildPlan(List<String> notes) {
        FocusPreferences active = preferences;
        List<FocusSession> history = plan.sessions().stream()
                .filter(session -> session.state().finished())
                .toList();
        FocusPlan generated = planner.plan(
                new FocusPlannerInput(classes, remainingTasksAfterHistory(history),
                        List.copyOf(prioritizedSubjects)),
                active,
                LocalDateTime.now(clock));
        if (active.planUntil() != null || active.planFrom() != null) {
            // A plan scope belongs to the request that produced this plan, not to
            // every plan afterwards, so it is consumed here.
            preferences = active.withPlanScope(null, null);
        }
        List<String> warnings = new ArrayList<>(notes);
        warnings.addAll(generated.warnings());
        List<FocusSession> sessions = new ArrayList<>(history);
        sessions.addAll(generated.sessions());
        sessions.sort(sessionOrder());
        return new FocusPlan(sessions, warnings, generated.generatedOn());
    }

    /** Removes already completed minutes from matching work while retaining history. */
    private List<StudyTask> remainingTasksAfterHistory(List<FocusSession> history) {
        Map<String, Integer> completedMinutes = new LinkedHashMap<>();
        for (FocusSession session : history) {
            if (session.state() == FocusSessionState.COMPLETED) {
                completedMinutes.merge(FocusFormats.taskKey(session.subject(), session.title()),
                        session.durationMinutes(), Integer::sum);
            }
        }
        List<StudyTask> remaining = new ArrayList<>();
        for (StudyTask task : tasks) {
            String key = FocusFormats.taskKey(task.subject(), task.title());
            int minutes = Math.max(0, task.estimatedMinutes()
                    - completedMinutes.getOrDefault(key, 0));
            if (minutes > 0) {
                remaining.add(new StudyTask(task.subject(), task.title(), task.type(), minutes,
                        task.deadline(), task.priority()));
            }
        }
        return remaining;
    }

    private void mergeClasses(List<ClassSlot> imported, Map<String, String> displayNames) {
        Set<String> seen = new LinkedHashSet<>();
        List<ClassSlot> merged = new ArrayList<>(classes.size() + imported.size());
        for (ClassSlot classSlot : classes) {
            seen.add(classKey(classSlot));
            merged.add(classSlot);
        }
        for (ClassSlot classSlot : imported) {
            ClassSlot resolved = resolveClass(classSlot, displayNames);
            if (seen.add(classKey(resolved))) {
                merged.add(resolved);
            }
        }
        classes.clear();
        classes.addAll(merged);
    }

    /**
     * Merges by canonical subject + title, so importing the same task again
     * updates its duration or deadline instead of creating a duplicate. A
     * replaced task keeps its durable id and the spelling already shown.
     */
    private void mergeTasks(List<StudyTask> imported, Map<String, String> displayNames) {
        Map<String, StudyTask> merged = new LinkedHashMap<>();
        Map<String, String> ids = new LinkedHashMap<>();
        for (StudyTask task : tasks) {
            String key = FocusFormats.taskKey(task.subject(), task.title());
            ids.putIfAbsent(key, taskIds.get(task));
            merged.put(key, task);
            displayNames.putIfAbsent(
                    SubjectNames.key(task.subject()), task.subject());
        }
        for (StudyTask task : imported) {
            StudyTask resolved = resolveTask(task, displayNames);
            String key = FocusFormats.taskKey(resolved.subject(), resolved.title());
            StudyTask existing = merged.get(key);
            if (existing != null && !existing.subject().equals(resolved.subject())) {
                resolved = new StudyTask(existing.subject(), resolved.title(),
                        resolved.type(), resolved.estimatedMinutes(),
                        resolved.deadline(), resolved.priority());
            }
            merged.put(key, resolved);
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
    private void addMissingReviewTasks(List<String> moreTimeSubjects, List<String> notes,
                                       Map<String, String> displayNames) {
        Set<String> subjectsWithTasks = new LinkedHashSet<>();
        for (StudyTask task : tasks) {
            subjectsWithTasks.add(FocusFormats.subjectKey(task.subject()));
        }

        Set<String> candidates = new LinkedHashSet<>();
        for (ClassSlot classSlot : classes) {
            candidates.add(resolveSubject(classSlot.subject(), displayNames));
        }
        if (moreTimeSubjects != null) {
            candidates.addAll(moreTimeSubjects);
        }

        LocalDate deadline = today().plusDays(FocusPlanner.DEFAULT_HORIZON_DAYS - 1L);
        List<String> added = new ArrayList<>();
        for (String subject : candidates) {
            String resolved = resolveSubject(subject, displayNames);
            if (resolved.isBlank()
                    || !subjectsWithTasks.add(FocusFormats.subjectKey(resolved))) {
                continue;
            }
            tasks.add(new StudyTask(resolved, "Review class notes", FocusType.QUICK,
                    REVIEW_TASK_MINUTES, deadline, Priority.MEDIUM));
            added.add(resolved);
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
            emphasised.add(FocusFormats.subjectKey(subject));
        }
        if (emphasised.isEmpty()) {
            return;
        }

        List<StudyTask> updated = new ArrayList<>(tasks.size());
        Set<String> matched = new LinkedHashSet<>();
        for (StudyTask task : tasks) {
            if (!emphasised.contains(FocusFormats.subjectKey(task.subject()))) {
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

    /**
     * First-seen spelling per canonical subject. Existing tasks and sessions
     * keep their original language while new imports adopt it, so one plan
     * never shows "Toán" and "Math" as two subjects.
     */
    private Map<String, String> subjectDisplayNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (StudyTask task : tasks) {
            names.putIfAbsent(SubjectNames.key(task.subject()), task.subject());
        }
        for (ClassSlot classSlot : classes) {
            names.putIfAbsent(SubjectNames.key(classSlot.subject()), classSlot.subject());
        }
        for (String subject : prioritizedSubjects) {
            names.putIfAbsent(SubjectNames.key(subject), subject);
        }
        for (FocusSession session : plan.sessions()) {
            names.putIfAbsent(SubjectNames.key(session.subject()), session.subject());
        }
        return names;
    }

    private static List<String> resolveSubjects(List<String> subjects,
                                                Map<String, String> displayNames) {
        if (subjects == null || subjects.isEmpty()) {
            return List.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String subject : subjects) {
            String name = resolveSubject(subject, displayNames);
            if (!name.isBlank()) {
                resolved.putIfAbsent(SubjectNames.key(name), name);
            }
        }
        return List.copyOf(resolved.values());
    }

    private static String resolveSubject(String subject, Map<String, String> displayNames) {
        String canonical = SubjectNames.canonical(subject);
        if (canonical.isBlank()) {
            return "";
        }
        String existing = displayNames.get(SubjectNames.key(canonical));
        String resolved = existing == null ? canonical : existing;
        displayNames.putIfAbsent(SubjectNames.key(resolved), resolved);
        return resolved;
    }

    private static ClassSlot resolveClass(ClassSlot classSlot,
                                          Map<String, String> displayNames) {
        String subject = resolveSubject(classSlot.subject(), displayNames);
        return subject.equals(classSlot.subject()) ? classSlot
                : new ClassSlot(subject, classSlot.day(), classSlot.start(), classSlot.end());
    }

    private static StudyTask resolveTask(StudyTask task, Map<String, String> displayNames) {
        String subject = resolveSubject(task.subject(), displayNames);
        return subject.equals(task.subject()) ? task
                : new StudyTask(subject, task.title(), task.type(), task.estimatedMinutes(),
                        task.deadline(), task.priority());
    }

    private static FocusSession resolveSessionSubject(FocusSession session,
                                                      Map<String, String> displayNames) {
        String subject = resolveSubject(session.subject(), displayNames);
        return subject.equals(session.subject()) ? session
                : new FocusSession(session.id(), subject, session.title(), session.type(),
                        session.date(), session.start(), session.durationMinutes(),
                        session.priority(), session.completed(), session.state());
    }

    private static String classKey(ClassSlot classSlot) {
        return SubjectNames.key(classSlot.subject()) + '\u0000'
                + classSlot.day() + '\u0000' + classSlot.start() + '\u0000' + classSlot.end();
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

    /** Convenience factory used by the Study panel for a manual session. */
    public static FocusSession newManualSession(String subject, String title, FocusType type,
                                                LocalDate date, java.time.LocalTime start,
                                                int minutes) {
        return new FocusSession(UUID.randomUUID().toString(), subject, title, type,
                date, start, minutes, Priority.MEDIUM, false);
    }
}
