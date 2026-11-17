package com.puppy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.ClassSlot;
import com.puppy.model.FocusExtraction;
import com.puppy.model.FocusPlannerInput;
import com.puppy.model.StudyTask;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

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
        List<ClassSlot> classes = parseClasses(root, notes);
        List<StudyTask> tasks = new ArrayList<>(parseTasks(root, today, notes));

        if (tasks.isEmpty()) {
            tasks.addAll(reviewTasksFromSubjects(root, today, notes));
        }

        if (classes.isEmpty() && tasks.isEmpty()) {
            throw new FocusPlanningException(
                    "I couldn't find any classes or tasks in the attached file. "
                            + "Add a timetable or a task list and try again.");
        }
        return new FocusExtraction(new FocusPlannerInput(classes, tasks), notes);
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

    private List<ClassSlot> parseClasses(JsonNode root, List<String> notes) {
        JsonNode nodes = firstArray(root, "classes", "classSlots", "timetable", "schedule");
        if (nodes == null) {
            return List.of();
        }
        List<ClassSlot> classes = new ArrayList<>();
        int index = 0;
        for (JsonNode node : nodes) {
            index++;
            String subject = text(node, "subject", "course", "module", "className", "name");
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

    private List<StudyTask> parseTasks(JsonNode root, LocalDate today, List<String> notes) {
        JsonNode nodes = firstArray(root, "tasks", "assignments", "homework", "deadlines",
                "studyTasks", "work");
        if (nodes == null) {
            return List.of();
        }
        List<StudyTask> tasks = new ArrayList<>();
        int index = 0;
        for (JsonNode node : nodes) {
            index++;
            String subject = text(node, "subject", "course", "module", "className");
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

    /**
     * A subject list without explicit tasks still deserves a plan. Create one
     * review session per subject, due in a week, so the timetable can be used.
     */
    private List<StudyTask> reviewTasksFromSubjects(JsonNode root, LocalDate today,
                                                    List<String> notes) {
        JsonNode nodes = firstArray(root, "subjects", "courses", "modules");
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        List<StudyTask> tasks = new ArrayList<>();
        for (JsonNode node : nodes) {
            String subject = node.isTextual()
                    ? node.asText().strip()
                    : text(node, "subject", "course", "name", "module");
            if (subject.isBlank()) {
                continue;
            }
            Priority priority = FocusFormats.parsePriority(
                    text(node, "priority", "importance"), Priority.MEDIUM);
            FocusType type = node.isObject()
                    ? FocusFormats.parseType(
                            text(node, "type", "sessionType", "kind", "focus", "note", "description"),
                            FocusType.QUICK)
                    : FocusType.QUICK;
            int minutes = type == FocusType.DEEP ? 60 : 50;
            tasks.add(new StudyTask(subject, "Review class notes", type,
                    minutes, today.plusDays(7), priority));
        }
        if (!tasks.isEmpty()) {
            notes.add("No tasks were listed, so I added a review session for each subject.");
        }
        return tasks;
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
        return type == FocusType.DEEP ? 60 : 30;
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
