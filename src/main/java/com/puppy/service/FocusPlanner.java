package com.puppy.service;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.FocusPlan;
import com.puppy.model.FocusPlannerInput;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.StudyTask;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic study planner.
 *
 * <p>The planner is pure Java and knows nothing about JavaFX or Ollama. It
 * places task blocks inside the user's daily free window while obeying five
 * rules:</p>
 *
 * <ol>
 *   <li>a session never overlaps a school class;</li>
 *   <li>a session never starts before, or ends after, the free window;</li>
 *   <li>work is finished on or before its deadline;</li>
 *   <li>time is spread across subjects instead of letting one subject fill
 *       every slot;</li>
 *   <li>each subject gets at most two sessions per day, with a ten-minute
 *       break between sessions.</li>
 * </ol>
 *
 * <p>Deep-focus tasks are split into roughly 50-minute blocks and quick-focus
 * tasks into roughly 25-minute blocks. Blocks are split evenly so a
 * 60-minute task becomes two 30-minute sessions rather than a 50 + 10 pair.</p>
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

        List<Chunk> remaining = new ArrayList<>(orderedChunks(tasks));
        List<FocusSession> sessions = new ArrayList<>();
        List<Chunk> missed = new ArrayList<>();
        Map<String, Integer> missedMinutes = new LinkedHashMap<>();

        LocalDate lastDate = horizonEnd(today, tasks, preferences);
        for (LocalDate date = today; !date.isAfter(lastDate) && !remaining.isEmpty();
             date = date.plusDays(1)) {
            // Anything whose deadline has passed can no longer satisfy the
            // "finish before the deadline" rule; report it instead of quietly
            // scheduling it late.
            for (Iterator<Chunk> iterator = remaining.iterator(); iterator.hasNext(); ) {
                Chunk chunk = iterator.next();
                if (chunk.task().deadline() != null && date.isAfter(chunk.task().deadline())) {
                    iterator.remove();
                    missed.add(chunk);
                    missedMinutes.merge(chunk.task().subject(), chunk.minutes(), Integer::sum);
                }
            }
            if (remaining.isEmpty()) {
                break;
            }

            List<Interval> free = freeIntervals(date, preferences, input.classes(), now);
            if (free.isEmpty()) {
                continue;
            }

            Map<String, Integer> perSubject = new HashMap<>();
            boolean placedInPass = true;
            while (placedInPass && !remaining.isEmpty()) {
                placedInPass = false;
                for (Iterator<Chunk> iterator = remaining.iterator(); iterator.hasNext(); ) {
                    Chunk chunk = iterator.next();
                    String subject = chunk.task().subject();
                    if (perSubject.getOrDefault(subject, 0)
                            >= MAX_SESSIONS_PER_SUBJECT_PER_DAY) {
                        continue;
                    }
                    Interval slot = firstFit(free, chunk.minutes());
                    if (slot == null) {
                        continue;
                    }
                    FocusSession session = toSession(chunk, date, slot.start());
                    sessions.add(session);
                    consume(free, slot, chunk.minutes());
                    perSubject.merge(subject, 1, Integer::sum);
                    iterator.remove();
                    placedInPass = true;
                }
            }
        }

        if (!remaining.isEmpty()) {
            missed.addAll(remaining);
            for (Chunk chunk : remaining) {
                missedMinutes.merge(chunk.task().subject(), chunk.minutes(), Integer::sum);
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
    private static LocalDate horizonEnd(LocalDate today, List<StudyTask> tasks,
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
            last = today.plusDays(DEFAULT_HORIZON_DAYS - 1L);
        }
        if (last.isBefore(today)) {
            last = today;
        }
        LocalDate cap = today.plusDays(MAX_HORIZON_DAYS - 1L);
        return last.isAfter(cap) ? cap : last;
    }

    /**
     * Sorts by deadline then priority, then round-robins across subjects. That
     * keeps the urgent work first without letting one subject consume the
     * whole plan.
     */
    static List<Chunk> orderedChunks(List<StudyTask> tasks) {
        Comparator<StudyTask> urgency = Comparator
                .comparing((StudyTask task) -> task.deadline() == null
                        ? LocalDate.MAX : task.deadline())
                .thenComparing(Comparator.comparingInt(
                        (StudyTask task) -> task.priority().rank()).reversed())
                .thenComparing(StudyTask::subject, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(StudyTask::title, String.CASE_INSENSITIVE_ORDER);

        Map<String, List<Chunk>> bySubject = new LinkedHashMap<>();
        tasks.stream().sorted(urgency).forEach(task -> bySubject
                .computeIfAbsent(task.subject(), key -> new ArrayList<>())
                .addAll(split(task)));

        List<Chunk> ordered = new ArrayList<>();
        boolean added = true;
        while (added) {
            added = false;
            for (List<Chunk> chunks : bySubject.values()) {
                if (!chunks.isEmpty()) {
                    ordered.add(chunks.remove(0));
                    added = true;
                }
            }
        }
        return ordered;
    }

    static List<Chunk> split(StudyTask task) {
        int total = task.estimatedMinutes();
        int maximum = task.type().defaultBlockMinutes();
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
    private static void consume(List<Interval> intervals, Interval used, int minutes) {
        LocalTime newStart = used.start().plusMinutes(minutes + SESSION_GAP_MINUTES);
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
            List<LocalDate> deadlines = missed.stream()
                    .filter(chunk -> chunk.task().subject().equals(entry.getKey()))
                    .map(chunk -> chunk.task().deadline())
                    .filter(Objects::nonNull)
                    .toList();
            StringBuilder warning = new StringBuilder()
                    .append(entry.getKey()).append(": ")
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
