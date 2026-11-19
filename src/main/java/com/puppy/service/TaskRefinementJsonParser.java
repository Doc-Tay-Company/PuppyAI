package com.puppy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppy.enums.Priority;
import com.puppy.model.TaskRefinementReply;
import com.puppy.model.TodoTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Validates the model's strict task-refinement response. */
public final class TaskRefinementJsonParser {
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskRefinementReply parse(String response, String proposalId)
            throws TaskRefinementException {
        try {
            String json = unwrap(response);
            JsonNode root = mapper.readTree(json);
            TaskRefinementReply.Kind kind = TaskRefinementReply.Kind.valueOf(
                    requiredText(root, "kind").toUpperCase());
            String message = requiredText(root, "message");
            List<String> issues = strings(root.path("issues"));
            if (kind == TaskRefinementReply.Kind.CLARIFY) {
                return new TaskRefinementReply(kind, message, null, issues);
            }
            JsonNode task = root.path("task");
            if (!task.isObject()) {
                throw new IllegalArgumentException("task must be an object");
            }
            String id = proposalId == null || proposalId.isBlank()
                    ? UUID.randomUUID().toString() : proposalId;
            TodoTask proposal = new TodoTask(
                    id,
                    requiredText(task, "title"),
                    text(task, "description"),
                    defaultText(task, "category", "General"),
                    parsePriority(defaultText(task, "priority", "MEDIUM")),
                    text(task, "completionCriteria"),
                    strings(task.path("checklist")),
                    strings(task.path("tags")),
                    false);
            return new TaskRefinementReply(kind, message, proposal, issues);
        } catch (TaskRefinementException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskRefinementException(
                    "The AI returned an invalid task draft. Please try refinement again.", e);
        }
    }

    private static Priority parsePriority(String value) {
        try {
            return Priority.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Priority.MEDIUM;
        }
    }

    private static String unwrap(String value) throws TaskRefinementException {
        if (value == null || value.isBlank()) {
            throw new TaskRefinementException("The AI returned an empty task draft.");
        }
        String text = value.strip();
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (newline >= 0 && closing > newline) {
                text = text.substring(newline + 1, closing).strip();
            }
        }
        return text;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String defaultText(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value.isEmpty() ? fallback : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().strip() : "";
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().strip());
            }
        }
        return List.copyOf(values);
    }
}
