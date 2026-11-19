package com.puppy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.FocusEditAction;
import com.puppy.model.FocusEditResult;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the JSON that the AI plan-editor model returns and turns it into a
 * list of concrete {@link FocusEditAction}s the caller can apply to
 * {@link FocusService}.
 *
 * <p>The parser is lenient: it skips actions that cannot be understood instead
 * of rejecting the entire response, and it strips Markdown fences the model
 * occasionally wraps around the JSON.</p>
 */
final class FocusPlanEditParser {

    private final ObjectMapper mapper = new ObjectMapper();

    FocusEditResult parse(String rawReply, LocalDate today) throws FocusPlanningException {
        if (rawReply == null || rawReply.isBlank()) {
            throw new FocusPlanningException(
                    "The model returned an empty edit response. Try rephrasing your request.");
        }

        JsonNode root = readJson(rawReply);
        List<FocusEditAction> actions = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // Parse the summary the AI wrote for the user.
        String summary = textOrEmpty(root, "summary", "message", "explanation");

        // Parse the actions array.
        JsonNode actionsNode = root.get("actions");
        if (actionsNode == null || !actionsNode.isArray()) {
            // Try the root itself when the model returns a single action.
            if (root.has("type")) {
                FocusEditAction single = parseAction(root, today, notes);
                if (single != null) {
                    actions.add(single);
                }
            }
        } else {
            int index = 0;
            for (JsonNode node : actionsNode) {
                index++;
                FocusEditAction action = parseAction(node, today, notes);
                if (action != null) {
                    actions.add(action);
                } else {
                    notes.add("Skipped action " + index + ": not enough information.");
                }
            }
        }

        if (actions.isEmpty() && summary.isBlank()) {
            throw new FocusPlanningException(
                    "I couldn't understand the edit the model suggested. "
                            + "Try a clearer request like 'move Math to 19:00' or "
                            + "'remove the Physics session on Wednesday'.");
        }

        if (!notes.isEmpty() && !summary.isBlank()) {
            summary = summary + "\n" + String.join("\n", notes);
        } else if (!notes.isEmpty()) {
            summary = String.join("\n", notes);
        }

        return new FocusEditResult(actions, summary);
    }

    private FocusEditAction parseAction(JsonNode node, LocalDate today,
                                        List<String> notes) {
        String typeText = textOrEmpty(node, "type", "action", "operation").toUpperCase();
        FocusEditAction.Type type = switch (typeText) {
            case "MOVE", "RESCHEDULE", "MOVE_SESSION" ->
                    FocusEditAction.Type.MOVE;
            case "RESIZE", "LENGTHEN", "SHORTEN", "CHANGE_DURATION" ->
                    FocusEditAction.Type.RESIZE;
            case "REMOVE", "DELETE", "CANCEL" ->
                    FocusEditAction.Type.REMOVE;
            case "CHANGE_SUBJECT", "SWITCH_SUBJECT", "RENAME_SUBJECT" ->
                    FocusEditAction.Type.CHANGE_SUBJECT;
            case "CHANGE_TYPE", "SET_MODE", "CHANGE_MODE", "SET_FOCUS_TYPE" ->
                    FocusEditAction.Type.CHANGE_TYPE;
            case "ADD", "INSERT", "CREATE" ->
                    FocusEditAction.Type.ADD;
            case "ADD_TASK", "CREATE_TASK", "ADD_SUBJECT" ->
                    FocusEditAction.Type.ADD_TASK;
            case "REMOVE_TASK", "DELETE_TASK", "REMOVE_SUBJECT", "DROP_SUBJECT" ->
                    FocusEditAction.Type.REMOVE_TASK;
            case "PRIORITIZE_SUBJECT", "PRIORITIZE", "SET_PRIORITY" ->
                    FocusEditAction.Type.PRIORITIZE_SUBJECT;
            case "SET_WINDOW", "CHANGE_WINDOW", "SET_FREE_TIME", "CHANGE_FREE_TIME" ->
                    FocusEditAction.Type.SET_WINDOW;
            case "SET_BREAK", "CHANGE_BREAK", "ADJUST_BREAK" ->
                    FocusEditAction.Type.SET_BREAK;
            case "SET_SESSION_LIMIT", "CHANGE_SESSION_LIMIT", "MAX_SESSION" ->
                    FocusEditAction.Type.SET_SESSION_LIMIT;
            case "UPDATE", "EDIT", "CHANGE", "MODIFY" ->
                    FocusEditAction.Type.UPDATE;
            default -> null;
        };
        if (type == null) {
            return null;
        }

        int sessionIndex = intOrDefault(node, 0,
                "sessionIndex", "index", "session", "number", "id");

        if (type == FocusEditAction.Type.SET_WINDOW) {
            LocalTime earliest = parseWindowTime(node,
                    "earliest", "from", "windowStart", "start", "newStart");
            LocalTime latest = parseWindowTime(node,
                    "latest", "to", "windowEnd", "end", "newEnd");
            if (earliest == null || latest == null) {
                notes.add("Skipped a window change: both start and end times are needed.");
                return null;
            }
            try {
                return FocusEditAction.setWindow(earliest, latest);
            } catch (IllegalArgumentException e) {
                notes.add("Skipped a window change: " + e.getMessage());
                return null;
            }
        }

        String subject = textOrEmpty(node, "subject", "course", "module", "newSubject");
        String title = textOrEmpty(node, "title", "task", "name", "description");
        FocusType focusType = parseFocusType(node);
        LocalDate date = parseDate(node, today);
        LocalTime start = parseTime(node);
        Integer durationMinutes = parseMinutes(node);
        Priority priority = parsePriority(node);

        String missing = switch (type) {
            case MOVE -> date == null && start == null ? "a new date or start time" : null;
            case RESIZE -> durationMinutes == null ? "a duration" : null;
            case CHANGE_SUBJECT -> subject.isBlank() ? "a subject" : null;
            case CHANGE_TYPE -> focusType == null ? "a focus type" : null;
            case ADD -> subject.isBlank() || title.isBlank()
                    ? "a subject and title" : null;
            case ADD_TASK -> subject.isBlank() ? "a subject" : null;
            case REMOVE_TASK, PRIORITIZE_SUBJECT -> subject.isBlank() ? "a subject" : null;
            case SET_BREAK, SET_SESSION_LIMIT -> durationMinutes == null ? "minutes" : null;
            default -> null;
        };
        if (missing != null) {
            notes.add("Skipped " + type + " action " + sessionIndex + ": missing " + missing + ".");
            return null;
        }

        try {
            return new FocusEditAction(type, sessionIndex, subject, title,
                    focusType, date, start, durationMinutes, priority);
        } catch (IllegalArgumentException e) {
            notes.add("Skipped an action: " + e.getMessage());
            return null;
        }
    }

    private JsonNode readJson(String rawReply) throws FocusPlanningException {
        String text = rawReply.strip();
        // Strip Markdown fences.
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                text = text.substring(firstLine + 1, lastFence).strip();
            }
        }

        int objectStart = text.indexOf('{');
        if (objectStart < 0) {
            throw new FocusPlanningException(
                    "The model didn't return a JSON edit response. Try again.");
        }
        int objectEnd = text.lastIndexOf('}');
        if (objectEnd <= objectStart) {
            throw new FocusPlanningException(
                    "The model returned incomplete JSON. Try again.");
        }

        try {
            return mapper.readTree(text.substring(objectStart, objectEnd + 1));
        } catch (IOException e) {
            throw new FocusPlanningException(
                    "I couldn't read the edit the model produced. Try a simpler request.", e);
        }
    }

    private static FocusType parseFocusType(JsonNode node) {
        String value = textOrEmpty(node, "focusType", "type", "sessionType", "kind");
        if (value.isBlank()) {
            return null;
        }
        return FocusFormats.parseType(value, null);
    }

    private static LocalDate parseDate(JsonNode node, LocalDate today) {
        String value = textOrEmpty(node, "date", "day", "sessionDate", "newDate");
        if (value.isBlank()) {
            return null;
        }
        try {
            return FocusFormats.parseDate(value, today);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalTime parseWindowTime(JsonNode node, String... names) {
        String value = textOrEmpty(node, names);
        if (value.isBlank()) {
            return null;
        }
        try {
            return FocusFormats.parseTime(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalTime parseTime(JsonNode node) {
        String value = textOrEmpty(node, "start", "startTime", "time", "newStart", "newTime");
        if (value.isBlank()) {
            return null;
        }
        try {
            return FocusFormats.parseTime(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer parseMinutes(JsonNode node) {
        for (String field : new String[]{
                "durationMinutes", "minutes", "duration", "newDuration"}) {
            JsonNode value = node.get(field);
            if (value != null && value.isNumber() && value.asInt() > 0) {
                return Math.min(value.asInt(), 600);
            }
        }
        return null;
    }

    private static Priority parsePriority(JsonNode node) {
        String value = textOrEmpty(node, "priority", "importance");
        if (value.isBlank()) {
            return null;
        }
        return FocusFormats.parsePriority(value, null);
    }

    private static int intOrDefault(JsonNode node, int defaultValue, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) {
                return value.asInt(defaultValue);
            }
        }
        return defaultValue;
    }

    private static String textOrEmpty(JsonNode node, String... names) {
        if (node == null) {
            return "";
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().strip();
            }
        }
        return "";
    }
}
