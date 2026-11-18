package com.puppy.service;

import com.puppy.document.DocumentExtractionException;
import com.puppy.document.DocumentTextExtractor;
import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.DocumentAttachment;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

    private final FocusPlanExtractor extractor;
    private final DocumentTextExtractor documentTextExtractor;
    private final FocusPlanner planner = new FocusPlanner();
    private final Clock clock;
    private final FocusRepository repository;
    private final Map<ClassSlot, String> classIds = new LinkedHashMap<>();
    private final Map<StudyTask, String> taskIds = new LinkedHashMap<>();

    private final List<ClassSlot> classes = new ArrayList<>();
    private final List<StudyTask> tasks = new ArrayList<>();
    private FocusPreferences preferences = FocusPreferences.defaults();
    private FocusPlan plan;

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

        synchronized (this) {
            return mutate(() -> {
                mergeClasses(extraction.input().classes());
                mergeTasks(extraction.input().tasks());
                addReviewTasksIfNeeded(notes);
                if (override != null) {
                    for (StudyTask task : tasks) {
                        taskIds.putIfAbsent(task.withType(override),
                                taskIds.getOrDefault(task, UUID.randomUUID().toString()));
                    }
                    tasks.replaceAll(task -> task.withType(override));
                }
                plan = buildPlan(notes);
                return plan;
            });
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
        return mutate(() -> {
            plan = buildPlan(List.of());
            return plan;
        });
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

    public synchronized void setPreferences(FocusPreferences newPreferences) {
        mutate(() -> preferences = Objects.requireNonNull(newPreferences, "newPreferences"));
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
        mutate(() -> plan = new FocusPlan(updated, plan.warnings(), plan.generatedOn()));
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
        mutate(() -> plan = new FocusPlan(sessions, plan.warnings(), plan.generatedOn()));
        return merged;
    }

    public synchronized FocusSession completeSession(String id) throws FocusPlanningException {
        List<FocusSession> updated = new ArrayList<>(plan.sessions());
        for (int i = 0; i < updated.size(); i++) {
            FocusSession session = updated.get(i);
            if (session.id().equals(id)) {
                FocusSession completed = session.asCompleted();
                updated.set(i, completed);
                mutate(() -> plan = new FocusPlan(updated, plan.warnings(), plan.generatedOn()));
                return completed;
            }
        }
        throw new FocusPlanningException("That session is no longer in the plan.");
    }

    public synchronized void removeSession(String id) {
        mutate(() -> plan = new FocusPlan(
                plan.sessions().stream().filter(session -> !session.id().equals(id)).toList(),
                plan.warnings(),
                plan.generatedOn()));
    }

    public synchronized void clearSessions() {
        mutate(() -> plan = new FocusPlan(List.of(), List.of("The session list is empty. "
                + "Press Generate plan to rebuild it."), plan.generatedOn()));
    }

    private <T> T mutate(Supplier<T> mutation) {
        FocusState before = snapshot();
        try {
            T result = mutation.get();
            FocusState after = snapshot();
            if (repository != null) {
                repository.save(after);
            }
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
                preferences, plan);
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

    private FocusPlan buildPlan(List<String> notes) {
        FocusPreferences active = preferences;
        FocusPlan generated = planner.plan(
                new FocusPlannerInput(classes, tasks),
                active,
                LocalDateTime.now(clock));
        if (active.planUntil() != null) {
            // A plan end belongs to the request that produced this plan, not to
            // every plan afterwards, so it is consumed here.
            preferences = active.withPlanUntil(null);
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

    private void mergeTasks(List<StudyTask> imported) {
        Set<StudyTask> unique = new LinkedHashSet<>(tasks);
        unique.addAll(imported);
        tasks.clear();
        tasks.addAll(unique);
    }

    /**
     * A timetable without a task list is still useful. Add one review task per
     * class subject so the planner can create quick-focus sessions.
     */
    private void addReviewTasksIfNeeded(List<String> notes) {
        if (!tasks.isEmpty()) {
            return;
        }
        Set<String> subjects = new LinkedHashSet<>();
        for (ClassSlot classSlot : classes) {
            subjects.add(classSlot.subject());
        }
        if (subjects.isEmpty()) {
            return;
        }
        LocalDate deadline = today().plusDays(FocusPlanner.DEFAULT_HORIZON_DAYS - 1L);
        for (String subject : subjects) {
            tasks.add(new StudyTask(subject, "Review class notes", FocusType.QUICK,
                    REVIEW_TASK_MINUTES, deadline, Priority.MEDIUM));
        }
        notes.add("No tasks were listed, so I added a review session for each subject.");
    }

    private void validatePlacement(FocusSession session, String ignoredId)
            throws FocusPlanningException {
        if (session.date().isBefore(today())) {
            throw new FocusPlanningException("That date is in the past.");
        }
        if (session.start().isBefore(preferences.earliest())
                || session.end().isAfter(preferences.latest())) {
            throw new FocusPlanningException(
                    "That session is outside your free time (%s - %s).".formatted(
                            FocusFormats.formatTime(preferences.earliest()),
                            FocusFormats.formatTime(preferences.latest())));
        }
        for (ClassSlot classSlot : classes) {
            if (classSlot.overlaps(session.date().getDayOfWeek(),
                    session.start(), session.end())) {
                throw new FocusPlanningException(
                        "That time overlaps your %s class.".formatted(classSlot.subject()));
            }
        }
        for (FocusSession existing : plan.sessions()) {
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
