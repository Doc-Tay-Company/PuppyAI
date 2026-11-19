package com.puppy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.FocusExtraction;
import com.puppy.model.FocusPlannerInput;
import com.puppy.model.StudyTask;
import com.puppy.util.SubjectNames;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the strict JSON prompt response from the local model.
 *
 * <p>Models occasionally wrap JSON in a code fence or add a sentence before it,
 * so the parser extracts the first JSON value and then validates every field.
 * Invalid rows are skipped with a note instead of rejecting the whole file.</p>
 */
final class FocusJsonParser {

    private final ObjectMapper mapper = new ObjectMapper();

    FocusExtraction parse(String rawReply, LocalDate today) throws FocusPlanningException {
        JsonNode root = readJson(rawReply);
        if (root.has("plan") && root.get("plan").isObject()) {
            root = root.get("plan");
        }

        List<String> notes = new ArrayList<>();
        Map<String, String> aliases = subjectAliases(root);
        List<ClassSlot> classes = dedupeClasses(parseClasses(root, notes, aliases));
        List<StudyTask> tasks = new ArrayList<>(
                dedupeTasks(parseTasks(root, today, notes, aliases)));
        List<SubjectEntry> subjects = parseSubjects(root, aliases);
        List<String> moreTimeSubjects = mergeMoreTimeSubjects(
                subjects, parseMoreTimeSubjects(root, aliases), aliases);
        addMissingSubjectEntries(subjects, moreTimeSubjects);
        tasks.addAll(reviewTasksFromSubjects(subjects, tasks, today, notes));

        if (classes.isEmpty() && tasks.isEmpty()) {
            throw new FocusPlanningException(
                    "I couldn't find any classes or tasks in the attached file. "
                            + "Add a timetable or a task list and try again.");
        }
        return new FocusExtraction(
                new FocusPlannerInput(classes, tasks, moreTimeSubjects), notes);
    }

    private JsonNode readJson(String rawReply) throws FocusPlanningException {
        if (rawReply == null || rawReply.isBlank()) {
            throw new FocusPlanningException(
                    "The local model returned an empty plan. Try importing the file again.");
        }
        String text = rawReply.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                text = text.substring(firstLine + 1, lastFence).strip();
            }
        }

        int object = text.indexOf('{');
        int array = text.indexOf('[');
        int start;
        if (object < 0 && array < 0) {
            throw new FocusPlanningException(
                    "The local model didn't return JSON. Try a clearer timetable or task file.");
        }
        start = object < 0 ? array : array < 0 ? object : Math.min(object, array);
        int end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
        if (end <= start) {
            throw new FocusPlanningException(
                    "The local model returned incomplete JSON. Try importing the file again.");
        }

        try {
            JsonNode node = mapper.readTree(text.substring(start, end + 1));
            if (node == null || node.isNull()) {
                throw new FocusPlanningException("The local model returned an empty plan.");
            }
            return node;
        } catch (FocusPlanningException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new FocusPlanningException(
                    "I couldn't read the plan the local model produced. "
                            + "A simpler timetable or task list works best.", e);
        }
    }

    private List<ClassSlot> parseClasses(JsonNode root, List<String> notes,
                                         Map<String, String> aliases) {
        JsonNode nodes = firstArray(root, "classes", "classSlots", "timetable", "schedule");
        if (nodes == null) {
            return List.of();
        }
        List<ClassSlot> classes = new ArrayList<>();
        int index = 0;
        for (JsonNode node : nodes) {
            index++;
            String subject = resolveSubject(
                    text(node, "subject", "course", "module", "className", "name"), aliases);
            String dayText = text(node, "day", "weekday", "dayOfWeek");
            String startText = text(node, "start", "startTime", "from", "begin");
            String endText = text(node, "end", "endTime", "to", "finish");
            if (subject.isBlank() || dayText.isBlank() || startText.isBlank() || endText.isBlank()) {
                notes.add("Skipped class " + index + ": it is missing a subject, day, start or end.");
                continue;
            }
            try {
                DayOfWeek day = FocusFormats.parseDay(dayText);
                LocalTime start = FocusFormats.parseTime(startText);
                LocalTime end = FocusFormats.parseTime(endText);
                classes.add(new ClassSlot(subject, day, start, end));
            } catch (RuntimeException e) {
                notes.add("Skipped class " + index + " (" + subject + "): " + e.getMessage() + ".");
            }
        }
        return classes;
    }

    private List<StudyTask> parseTasks(JsonNode root, LocalDate today, List<String> notes,
                                       Map<String, String> aliases) {
        JsonNode nodes = firstArray(root, "tasks", "assignments", "homework", "deadlines",
                "studyTasks", "work");
        if (nodes == null) {
            return List.of();
        }
        List<StudyTask> tasks = new ArrayList<>();
        int index = 0;
        for (JsonNode node : nodes) {
            index++;
            String subject = resolveSubject(
                    text(node, "subject", "course", "module", "className"), aliases);
            String title = text(node, "title", "task", "name", "description", "topic", "activity");
            if (title.isBlank()) {
                title = "Study " + (subject.isBlank() ? "session" : subject);
            }
            if (subject.isBlank()) {
                notes.add("Skipped task " + index + " (" + title + "): it has no subject.");
                continue;
            }

            FocusType type = FocusFormats.parseType(
                    text(node, "type", "sessionType", "kind", "focus"),
                    inferType(title));
            int minutes = estimatedMinutes(node, type);
            LocalDate deadline = null;
            String deadlineText = text(node, "deadline", "due", "dueDate", "date");
            if (!deadlineText.isBlank()) {
                try {
                    deadline = FocusFormats.parseDate(deadlineText, today);
                } catch (RuntimeException e) {
                    notes.add("No deadline for \"" + title + "\": " + e.getMessage() + ".");
                }
            }
            Priority priority = FocusFormats.parsePriority(
                    text(node, "priority", "importance", "urgency"), Priority.MEDIUM);
            tasks.add(new StudyTask(subject, title, type, minutes, deadline, priority));
        }
        return tasks;
    }

    private List<SubjectEntry> parseSubjects(JsonNode root, Map<String, String> aliases) {
        JsonNode nodes = firstArray(root, "subjects", "courses", "modules");
        if (nodes == null || nodes.isEmpty()) {
            return new ArrayList<>();
        }
        List<SubjectEntry> subjects = new ArrayList<>();
        for (JsonNode node : nodes) {
            String subject = resolveSubject(node.isTextual()
                    ? node.asText().strip()
                    : text(node, "subject", "course", "name", "module"), aliases);
            if (subject.isBlank()) {
                continue;
            }
            Priority priority = node.isObject()
                    ? FocusFormats.parsePriority(
                            text(node, "priority", "importance"), Priority.MEDIUM)
                    : Priority.MEDIUM;
            FocusType type = node.isObject()
                    ? FocusFormats.parseType(
                            text(node, "type", "sessionType", "kind", "focus", "note", "description"),
                            FocusType.QUICK)
                    : FocusType.QUICK;
            boolean wantsMoreTime = node.isObject() && isTrue(node,
                    "moreTime", "needsMoreTime", "extraTime", "emphasized");
            subjects.add(new SubjectEntry(subject, type, priority, wantsMoreTime));
        }
        return subjects;
    }

    /**
     * The extraction names subjects that need extra time separately from tasks.
     * Accept both the dedicated array and a per-subject flag.
     */
    private List<String> parseMoreTimeSubjects(JsonNode root, Map<String, String> aliases) {
        Map<String, String> subjects = new LinkedHashMap<>();
        JsonNode nodes = firstArray(root, "moreTimeSubjects", "needsMoreTime",
                "extraTimeSubjects", "emphasizedSubjects");
        if (nodes != null) {
            for (JsonNode node : nodes) {
                String subject = node.isTextual()
                        ? node.asText().strip()
                        : text(node, "subject", "course", "name", "module");
                addUniqueSubject(subjects, subject, aliases);
            }
        } else if (root != null) {
            for (String field : List.of("moreTimeSubjects", "needsMoreTime",
                    "extraTimeSubjects", "emphasizedSubjects")) {
                JsonNode node = root.get(field);
                if (node != null && node.isTextual()) {
                    addUniqueSubject(subjects, node.asText().strip(), aliases);
                }
            }
        }
        return List.copyOf(subjects.values());
    }

    private List<String> mergeMoreTimeSubjects(List<SubjectEntry> subjects,
                                               List<String> explicit,
                                               Map<String, String> aliases) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String subject : explicit) {
            addUniqueSubject(merged, subject, aliases);
        }
        for (SubjectEntry entry : subjects) {
            if (entry.wantsMoreTime()) {
                addUniqueSubject(merged, entry.subject(), aliases);
            }
        }
        return List.copyOf(merged.values());
    }

    private void addMissingSubjectEntries(List<SubjectEntry> subjects,
                                          List<String> moreTimeSubjects) {
        Set<String> seen = new HashSet<>();
        for (SubjectEntry subject : subjects) {
            seen.add(SubjectNames.key(subject.subject()));
        }
        for (String subject : moreTimeSubjects) {
            if (seen.add(SubjectNames.key(subject))) {
                subjects.add(new SubjectEntry(
                        subject, FocusType.QUICK, Priority.MEDIUM, true));
            }
        }
    }

    /**
     * Every subject gets a review task, even when the extraction also returned
     * explicit work. The task is due in a week and merged with later imports by
     * subject and title, so repeated imports update it instead of duplicating it.
     */
    private List<StudyTask> reviewTasksFromSubjects(List<SubjectEntry> subjects,
                                                    List<StudyTask> existing,
                                                    LocalDate today, List<String> notes) {
        if (subjects.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new HashSet<>();
        for (StudyTask task : existing) {
            keys.add(FocusFormats.taskKey(task.subject(), task.title()));
        }
        List<StudyTask> tasks = new ArrayList<>();
        for (SubjectEntry subject : subjects) {
            String key = FocusFormats.taskKey(subject.subject(), "Review class notes");
            if (!keys.add(key)) {
                continue;
            }
            int minutes = subject.type() == FocusType.DEEP ? 60
                    : subject.type() == FocusType.EXAM ? 45 : 50;
            tasks.add(new StudyTask(subject.subject(), "Review class notes", subject.type(),
                    minutes, today.plusDays(FocusPlanner.DEFAULT_HORIZON_DAYS - 1L),
                    subject.priority()));
        }
        if (!tasks.isEmpty()) {
            notes.add(existing.isEmpty()
                    ? "No tasks were listed, so I added a review session for each subject."
                    : "Added a review session for each subject so none is skipped.");
        }
        return tasks;
    }

    private static void addUniqueSubject(Map<String, String> subjects, String value,
                                         Map<String, String> aliases) {
        String resolved = resolveSubject(value, aliases);
        if (resolved.isBlank()) {
            return;
        }
        subjects.putIfAbsent(SubjectNames.key(resolved), resolved);
    }

    /**
     * Reads optional aliases the model attached to a subject, so a file that
     * lists both translations still produces one subject. The alias array is
     * accepted as either strings or objects with a subject/name field.
     */
    private Map<String, String> subjectAliases(JsonNode root) {
        JsonNode nodes = firstArray(root, "subjects", "courses", "modules");
        if (nodes == null || nodes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            if (!node.isObject()) {
                continue;
            }
            String canonical = text(node, "subject", "course", "name", "module");
            if (canonical.isBlank()) {
                continue;
            }
            String resolved = resolveSubject(canonical, aliases);
            aliases.put(SubjectNames.key(resolved), resolved);
            JsonNode aliasNodes = node.get("aliases");
            if (aliasNodes == null) {
                aliasNodes = node.get("alias");
            }
            if (aliasNodes == null) {
                continue;
            }
            if (aliasNodes.isTextual()) {
                aliases.putIfAbsent(SubjectNames.key(aliasNodes.asText()), resolved);
            } else if (aliasNodes.isArray()) {
                for (JsonNode alias : aliasNodes) {
                    String name = alias.isTextual()
                            ? alias.asText()
                            : text(alias, "subject", "course", "name", "module");
                    if (!name.isBlank()) {
                        aliases.putIfAbsent(SubjectNames.key(name), resolved);
                    }
                }
            }
        }
        return aliases;
    }

    private static String resolveSubject(String value, Map<String, String> aliases) {
        String cleaned = value == null ? "" : value.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ").strip();
        if (cleaned.isEmpty()) {
            return "";
        }
        String canonical = aliases.get(SubjectNames.key(cleaned));
        if (canonical != null) {
            return canonical;
        }
        canonical = SubjectNames.canonical(cleaned);
        String aliased = aliases.get(SubjectNames.key(canonical));
        return aliased == null ? canonical : aliased;
    }

    private static List<ClassSlot> dedupeClasses(List<ClassSlot> classes) {
        Map<String, ClassSlot> unique = new LinkedHashMap<>();
        for (ClassSlot classSlot : classes) {
            unique.put(classKey(classSlot), classSlot);
        }
        return List.copyOf(unique.values());
    }

    private static String classKey(ClassSlot classSlot) {
        return SubjectNames.key(classSlot.subject()) + '\u0000'
                + classSlot.day() + '\u0000' + classSlot.start() + '\u0000' + classSlot.end();
    }

    private static List<StudyTask> dedupeTasks(List<StudyTask> tasks) {
        Map<String, StudyTask> unique = new LinkedHashMap<>();
        for (StudyTask task : tasks) {
            unique.put(FocusFormats.taskKey(task.subject(), task.title()), task);
        }
        return List.copyOf(unique.values());
    }

    private static boolean isTrue(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isTextual()) {
                String text = FocusFormats.normalize(value.asText());
                if (text.equals("true") || text.equals("yes") || text.equals("1")) {
                    return true;
                }
            }
        }
        return false;
    }

    private record SubjectEntry(String subject, FocusType type, Priority priority,
                                boolean wantsMoreTime) {
    }

    private static int estimatedMinutes(JsonNode node, FocusType type) {
        for (String field : List.of("estimatedMinutes", "minutes", "durationMinutes",
                "duration", "estimatedTime")) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            try {
                int minutes = value.isNumber()
                        ? value.asInt()
                        : FocusFormats.parseMinutes(value.asText());
                if (minutes > 0) {
                    return Math.min(minutes, 600);
                }
            } catch (RuntimeException ignored) {
                // Fall through to the next field.
            }
        }
        for (String field : List.of("estimatedHours", "hours")) {
            JsonNode value = node.get(field);
            if (value != null && value.isNumber() && value.asDouble() > 0) {
                return (int) Math.min(600, Math.round(value.asDouble() * 60));
            }
        }
        return type == FocusType.DEEP ? 60 : type == FocusType.EXAM ? 45 : 30;
    }

    private static FocusType inferType(String title) {
        return FocusFormats.parseType(title, FocusType.QUICK);
    }

    private static JsonNode firstArray(JsonNode root, String... names) {
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            for (String name : names) {
                if (name.equals("tasks") || name.equals("assignments")
                        || name.equals("studyTasks")) {
                    return root;
                }
            }
            return null;
        }
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String... names) {
        if (node == null) {
            return "";
        }
        if (node.isTextual() || node.isNumber()) {
            return node.asText().strip();
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isTextual() || value.isNumber()) {
                return value.asText().strip();
            }
            if (value.isObject()) {
                String nested = text(value, "name", "title", "subject", "course");
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }
        return "";
    }
}
