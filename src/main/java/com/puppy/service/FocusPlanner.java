package com.puppy.service;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.FocusPlan;
import com.puppy.model.FocusPlannerInput;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.StudyTask;
import com.puppy.util.SubjectNames;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic study planner.
 *
 * <p>The planner is pure Java and knows nothing about JavaFX or Ollama. It
 * places task blocks inside the user's daily free window while obeying these
 * rules:</p>
 *
 * <ol>
 *   <li>a session never overlaps a school class;</li>
 *   <li>a session never starts before, or ends after, the free window;</li>
 *   <li>work is finished on or before its deadline;</li>
 *   <li>time is spread across subjects instead of letting one subject fill
 *       every slot;</li>
 *   <li>every subject gets at most one session in the first pass, and a second
 *       session only in the second pass, with a ten-minute break between
 *       sessions and at most two sessions per subject per day;</li>
 *   <li>the starting subject rotates each day, and while several subjects still
 *       need time a second session may not push a subject past half of that
 *       day's free window.</li>
 * </ol>
 *
 * <p>Tasks are split into session-sized blocks using the block length stored on
 * the task; new tasks use the neutral 25-minute block. Blocks are split evenly
 * so a 60-minute task becomes two 30-minute sessions rather than a 25 + 35
 * pair. The stored type is kept only so older plans still load.</p>
 */
public final class FocusPlanner {

    static final int SESSION_GAP_MINUTES = 10;
    static final int MAX_SESSIONS_PER_SUBJECT_PER_DAY = 2;
    static final int DEFAULT_HORIZON_DAYS = 7;
    static final int MAX_HORIZON_DAYS = 365;
    static final int START_LEAD_MINUTES = 15;
    private static final DateTimeFormatter DEADLINE_LABEL = DateTimeFormatter.ofPattern("d MMM");

    /**
     * Builds a plan starting from {@code now}. The clock is passed in rather
     * than read from the system so tests can run at a fixed moment.
     */
    public FocusPlan plan(FocusPlannerInput input, FocusPreferences preferences, LocalDateTime now) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(now, "now");

        LocalDate today = now.toLocalDate();
        List<StudyTask> tasks = input.tasks();
        if (tasks.isEmpty()) {
            return FocusPlan.empty(today,
                    "Add at least one task or import a subject file so Puppy has work to schedule.");
        }

        Set<String> prioritizedSubjects = new LinkedHashSet<>();
        if (input.moreTimeSubjects() != null) {
            for (String s : input.moreTimeSubjects()) {
                prioritizedSubjects.add(FocusFormats.subjectKey(s));
            }
        }
        for (StudyTask task : tasks) {
            if (task.priority() == Priority.HIGH) {
                prioritizedSubjects.add(FocusFormats.subjectKey(task.subject()));
            }
        }

        Map<String, List<Chunk>> queues = subjectQueues(tasks, preferences.maxSessionMinutes());
        List<String> subjectOrder = new ArrayList<>(queues.keySet());
        if (!prioritizedSubjects.isEmpty()) {
            List<String> prioritizedOrder = new ArrayList<>();
            for (String subjectKey : queues.keySet()) {
                if (prioritizedSubjects.contains(subjectKey)) {
                    prioritizedOrder.add(subjectKey);
                }
            }
            for (String subjectKey : queues.keySet()) {
                if (!prioritizedSubjects.contains(subjectKey)) {
                    prioritizedOrder.add(subjectKey);
                }
            }
            subjectOrder = prioritizedOrder;
        }

        List<FocusSession> sessions = new ArrayList<>();
        List<Chunk> missed = new ArrayList<>();
        Map<String, Integer> missedMinutes = new LinkedHashMap<>();

        LocalDate startDate = preferences.planFrom() != null && preferences.planFrom().isAfter(today)
                ? preferences.planFrom() : today;
        LocalDate lastDate = horizonEnd(startDate, tasks, preferences);
        int dayIndex = 0;
        for (LocalDate date = startDate; !date.isAfter(lastDate) && hasWork(queues);
             date = date.plusDays(1), dayIndex++) {
            // Anything whose deadline has passed can no longer satisfy the
            // "finish before the deadline" rule; report it instead of quietly
            // scheduling it late.
            expirePastDeadlines(queues, date, missed, missedMinutes);
            if (!hasWork(queues)) {
                break;
            }

            List<Interval> free = freeIntervals(date, preferences, input.classes(), now);
            if (free.isEmpty()) {
                continue;
            }

            int freeMinutes = totalMinutes(free);
            int activeSubjects = countSubjectsWithWork(queues);
            // While several subjects still need time, a second session may not
            // push one subject past half of today's free window.
            int secondSessionCap = activeSubjects > 1
                    ? Math.max(1, freeMinutes / 2)
                    : Integer.MAX_VALUE;
            int start = Math.floorMod(dayIndex, subjectOrder.size());
            Map<String, Integer> perSubject = new HashMap<>();
            Map<String, Integer> subjectMinutes = new HashMap<>();

            // Pass 1: at most one session per subject today.
            for (int offset = 0; offset < subjectOrder.size(); offset++) {
                String subject = subjectOrder.get(
                        Math.floorMod(start + offset, subjectOrder.size()));
                List<Chunk> queue = queues.get(subject);
                if (queue == null || queue.isEmpty()) {
                    continue;
                }
                placeOne(queue, free, date, sessions, perSubject, subjectMinutes,
                        Integer.MAX_VALUE, preferences.breakMinutes());
            }

            // Pass 2: a second session only for subjects that already had one.
            for (int offset = 0; offset < subjectOrder.size(); offset++) {
                String subject = subjectOrder.get(
                        Math.floorMod(start + offset, subjectOrder.size()));
                List<Chunk> queue = queues.get(subject);
                if (queue == null || queue.isEmpty()
                        || perSubject.getOrDefault(subject, 0)
                        != 1) {
                    continue;
                }
                int cap = prioritizedSubjects.contains(subject) ? Integer.MAX_VALUE : secondSessionCap;
                placeOne(queue, free, date, sessions, perSubject, subjectMinutes,
                        cap, preferences.breakMinutes());
            }

            // Pass 3: greedy fill for prioritized subjects to utilize remaining free time.
            if (!prioritizedSubjects.isEmpty()) {
                boolean placedAny;
                do {
                    placedAny = false;
                    for (int offset = 0; offset < subjectOrder.size(); offset++) {
                        String subject = subjectOrder.get(
                                Math.floorMod(start + offset, subjectOrder.size()));
                        if (!prioritizedSubjects.contains(subject)) {
                            continue;
                        }
                        List<Chunk> queue = queues.get(subject);
                        if (queue == null || queue.isEmpty()) {
                            continue;
                        }
                        if (placeOne(queue, free, date, sessions, perSubject, subjectMinutes,
                                Integer.MAX_VALUE, preferences.breakMinutes())) {
                            placedAny = true;
                        }
                    }
                } while (placedAny && !free.isEmpty() && hasWork(queues));
            }
        }

        for (List<Chunk> queue : queues.values()) {
            missed.addAll(queue);
            for (Chunk chunk : queue) {
                missedMinutes.merge(SubjectNames.key(chunk.task().subject()),
                        chunk.minutes(), Integer::sum);
            }
        }

        List<String> warnings = buildWarnings(today, missed, missedMinutes, sessions.isEmpty());
        sessions.sort(Comparator.comparing(FocusSession::date)
                .thenComparing(FocusSession::start)
                .thenComparing(FocusSession::subject, String.CASE_INSENSITIVE_ORDER));
        return new FocusPlan(sessions, warnings, today);
    }

    /**
     * The final day the planner may use: the user's explicit plan end when
     * present, otherwise the latest task deadline, otherwise one week from
     * today. An explicit request wins even when a task deadline is later.
     */
    private static LocalDate horizonEnd(LocalDate startDate, List<StudyTask> tasks,
                                        FocusPreferences preferences) {
        LocalDate requested = preferences.planUntil();
        LocalDate latestDeadline = tasks.stream()
                .map(StudyTask::deadline)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        LocalDate last;
        if (requested != null) {
            last = requested;
        } else if (latestDeadline != null) {
            last = latestDeadline;
        } else {
            last = startDate.plusDays(DEFAULT_HORIZON_DAYS - 1L);
        }
        if (last.isBefore(startDate)) {
            last = startDate;
        }
        LocalDate cap = startDate.plusDays(MAX_HORIZON_DAYS - 1L);
        return last.isAfter(cap) ? cap : last;
    }

    /**
     * Sorts by deadline then priority, then round-robins across subjects. That
     * keeps the urgent work first without letting one subject consume the
     * whole plan.
     */
    static List<Chunk> orderedChunks(List<StudyTask> tasks) {
        List<List<Chunk>> bySubject = new ArrayList<>(subjectQueues(tasks).values());
        List<Chunk> ordered = new ArrayList<>();
        boolean added = true;
        while (added) {
            added = false;
            for (List<Chunk> chunks : bySubject) {
                if (!chunks.isEmpty()) {
                    ordered.add(chunks.remove(0));
                    added = true;
                }
            }
        }
        return ordered;
    }

    /**
     * Queues the remaining blocks per subject, keyed by a normalised subject
     * name. Urgent tasks come first inside each queue.
     */
    static Map<String, List<Chunk>> subjectQueues(List<StudyTask> tasks) {
        return subjectQueues(tasks, Integer.MAX_VALUE);
    }

    private static Map<String, List<Chunk>> subjectQueues(List<StudyTask> tasks,
                                                           int maxSessionMinutes) {
        Comparator<StudyTask> urgency = Comparator
                .comparing((StudyTask task) -> task.deadline() == null
                        ? LocalDate.MAX : task.deadline())
                .thenComparing(Comparator.comparingInt(
                        (StudyTask task) -> task.priority().rank()).reversed())
                .thenComparing(StudyTask::subject, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(StudyTask::title, String.CASE_INSENSITIVE_ORDER);

        Map<String, List<Chunk>> bySubject = new LinkedHashMap<>();
        tasks.stream().sorted(urgency).forEach(task -> bySubject
                .computeIfAbsent(FocusFormats.subjectKey(task.subject()),
                        key -> new ArrayList<>())
                .addAll(split(task, maxSessionMinutes)));
        return bySubject;
    }

    private static boolean hasWork(Map<String, List<Chunk>> queues) {
        return queues.values().stream().anyMatch(queue -> !queue.isEmpty());
    }

    private static int countSubjectsWithWork(Map<String, List<Chunk>> queues) {
        return (int) queues.values().stream().filter(queue -> !queue.isEmpty()).count();
    }

    private static void expirePastDeadlines(Map<String, List<Chunk>> queues, LocalDate date,
                                            List<Chunk> missed,
                                            Map<String, Integer> missedMinutes) {
        for (List<Chunk> queue : queues.values()) {
            for (Iterator<Chunk> iterator = queue.iterator(); iterator.hasNext(); ) {
                Chunk chunk = iterator.next();
                if (chunk.task().deadline() != null && date.isAfter(chunk.task().deadline())) {
                    iterator.remove();
                    missed.add(chunk);
                    missedMinutes.merge(SubjectNames.key(chunk.task().subject()),
                        chunk.minutes(), Integer::sum);
                }
            }
        }
    }

    /**
     * Places at most one more block for this subject. It tries the next blocks
     * in urgency order and skips ones that do not fit or would exceed the
     * subject's daily cap.
     */
    private static boolean placeOne(List<Chunk> queue, List<Interval> free, LocalDate date,
                                    List<FocusSession> sessions,
                                    Map<String, Integer> perSubject,
                                    Map<String, Integer> subjectMinutes,
                                    int subjectCap, int breakMinutes) {
        for (int index = 0; index < queue.size(); index++) {
            Chunk chunk = queue.get(index);
            String subject = FocusFormats.subjectKey(chunk.task().subject());
            if (subjectMinutes.getOrDefault(subject, 0) + chunk.minutes() > subjectCap) {
                continue;
            }
            Interval slot = firstFit(free, chunk.minutes());
            if (slot == null) {
                continue;
            }
            sessions.add(toSession(chunk, date, slot.start()));
            consume(free, slot, chunk.minutes(), breakMinutes);
            perSubject.merge(subject, 1, Integer::sum);
            subjectMinutes.merge(subject, chunk.minutes(), Integer::sum);
            queue.remove(index);
            return true;
        }
        return false;
    }

    private static int totalMinutes(List<Interval> intervals) {
        return (int) intervals.stream()
                .mapToLong(interval -> Duration.between(interval.start(), interval.end()).toMinutes())
                .sum();
    }

    static List<Chunk> split(StudyTask task) {
        return split(task, Integer.MAX_VALUE);
    }

    private static List<Chunk> split(StudyTask task, int maxSessionMinutes) {
        int total = task.estimatedMinutes();
        int maximum = Math.min(task.type().defaultBlockMinutes(), maxSessionMinutes);
        if (total <= maximum) {
            return List.of(new Chunk(task, total));
        }
        int blocks = (total + maximum - 1) / maximum;
        int base = total / blocks;
        int remainder = total % blocks;
        List<Chunk> chunks = new ArrayList<>(blocks);
        for (int i = 0; i < blocks; i++) {
            chunks.add(new Chunk(task, base + (i < remainder ? 1 : 0)));
        }
        return chunks;
    }

    private static FocusSession toSession(Chunk chunk, LocalDate date, LocalTime start) {
        StudyTask task = chunk.task();
        return new FocusSession(
                UUID.randomUUID().toString(),
                task.subject(),
                task.title(),
                task.type(),
                date,
                start,
                chunk.minutes(),
                task.priority(),
                false);
    }

    /**
     * The free intervals for one date: the availability window, minus classes,
     * then minus time already needed by today. On the current day the window
     * starts after a short preparation lead.
     */
    static List<Interval> freeIntervals(LocalDate date, FocusPreferences preferences,
                                        List<ClassSlot> classes, LocalDateTime now) {
        LocalTime start = preferences.earliest();
        LocalTime end = preferences.latest();
        if (date.equals(now.toLocalDate())) {
            LocalTime earliestStart = ceilToFiveMinutes(
                    now.toLocalTime().plusMinutes(START_LEAD_MINUTES));
            if (earliestStart.isAfter(start)) {
                start = earliestStart;
            }
        }
        if (!start.isBefore(end)) {
            return List.of();
        }

        List<Interval> intervals = new ArrayList<>();
        intervals.add(new Interval(start, end));
        List<ClassSlot> dayClasses = classes.stream()
                .filter(slot -> slot.day() == date.getDayOfWeek())
                .sorted(Comparator.comparing(ClassSlot::start))
                .toList();
        for (ClassSlot slot : dayClasses) {
            intervals = subtract(intervals, slot.start(), slot.end());
            if (intervals.isEmpty()) {
                break;
            }
        }
        return intervals;
    }

    private static List<Interval> subtract(List<Interval> intervals, LocalTime removeStart,
                                           LocalTime removeEnd) {
        List<Interval> result = new ArrayList<>();
        for (Interval interval : intervals) {
            if (!removeStart.isBefore(interval.end()) || !interval.start().isBefore(removeEnd)) {
                result.add(interval);
                continue;
            }
            if (interval.start().isBefore(removeStart)) {
                result.add(new Interval(interval.start(), removeStart));
            }
            if (removeEnd.isBefore(interval.end())) {
                result.add(new Interval(removeEnd, interval.end()));
            }
        }
        return result;
    }

    private static Interval firstFit(List<Interval> intervals, int minutes) {
        for (Interval interval : intervals) {
            if (Duration.between(interval.start(), interval.end()).toMinutes() >= minutes) {
                return interval;
            }
        }
        return null;
    }

    /** Removes the used prefix and the break that follows it. */
    private static void consume(List<Interval> intervals, Interval used, int minutes,
                                int breakMinutes) {
        LocalTime newStart = used.start().plusMinutes(minutes + breakMinutes);
        int index = intervals.indexOf(used);
        if (index < 0) {
            return;
        }
        if (!newStart.isBefore(used.end())) {
            intervals.remove(index);
        } else {
            intervals.set(index, new Interval(newStart, used.end()));
        }
    }

    private static LocalTime ceilToFiveMinutes(LocalTime time) {
        int remainder = time.getMinute() % 5;
        int add = remainder == 0 ? 0 : 5 - remainder;
        return time.plusMinutes(add).withSecond(0).withNano(0);
    }

    private static List<String> buildWarnings(LocalDate today, List<Chunk> missed,
                                              Map<String, Integer> missedMinutes,
                                              boolean noSessions) {
        List<String> warnings = new ArrayList<>();
        if (noSessions) {
            warnings.add("I couldn't find a free slot inside your availability window. "
                    + "Try widening the free time or removing a class.");
        }
        for (Map.Entry<String, Integer> entry : missedMinutes.entrySet()) {
            String displayName = missed.stream()
                    .map(chunk -> chunk.task().subject())
                    .filter(subject -> SubjectNames.key(subject).equals(entry.getKey()))
                    .findFirst()
                    .orElse(entry.getKey());
            List<LocalDate> deadlines = missed.stream()
                    .filter(chunk -> SubjectNames.key(chunk.task().subject())
                            .equals(entry.getKey()))
                    .map(chunk -> chunk.task().deadline())
                    .filter(Objects::nonNull)
                    .toList();
            StringBuilder warning = new StringBuilder()
                    .append(displayName).append(": ")
                    .append(entry.getValue()).append(" more minutes could not be placed.");
            if (!deadlines.isEmpty()) {
                LocalDate earliest = deadlines.stream().min(LocalDate::compareTo).orElse(today);
                if (earliest.isBefore(today)) {
                    warning.append(" Its deadline has already passed.");
                } else {
                    warning.append(" Deadline: ")
                            .append(DEADLINE_LABEL.format(earliest)).append('.');
                }
            }
            warnings.add(warning.toString());
        }
        return List.copyOf(warnings);
    }

    /** A task split into one schedulable block. */
    record Chunk(StudyTask task, int minutes) {
    }

    /** A free range on one day. */
    record Interval(LocalTime start, LocalTime end) {
    }
}
