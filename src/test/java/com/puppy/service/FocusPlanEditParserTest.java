package com.puppy.service;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.FocusEditAction;
import com.puppy.model.FocusEditResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusPlanEditParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    private final FocusPlanEditParser parser = new FocusPlanEditParser();

    @Test
    void parsesUpdateAction() throws Exception {
        String json = """
                {
                  "actions": [
                    {
                      "type": "UPDATE",
                      "sessionIndex": 1,
                      "start": "19:00",
                      "durationMinutes": 60
                    }
                  ],
                  "summary": "Moved session 1 to 19:00."
                }
                """;
        FocusEditResult result = parser.parse(json, TODAY);

        assertFalse(result.isEmpty());
        assertEquals("Moved session 1 to 19:00.", result.summary());
        assertEquals(1, result.actions().size());

        FocusEditAction action = result.actions().get(0);
        assertEquals(FocusEditAction.Type.UPDATE, action.type());
        assertEquals(1, action.sessionIndex());
        assertEquals(LocalTime.of(19, 0), action.start());
        assertEquals(60, action.durationMinutes());
    }

    @Test
    void parsesRemoveAction() throws Exception {
        String json = """
                {
                  "actions": [
                    {
                      "type": "REMOVE",
                      "sessionIndex": 2
                    }
                  ],
                  "summary": "Removed session 2."
                }
                """;
        FocusEditResult result = parser.parse(json, TODAY);

        assertFalse(result.isEmpty());
        assertEquals(1, result.actions().size());
        FocusEditAction action = result.actions().get(0);
        assertEquals(FocusEditAction.Type.REMOVE, action.type());
        assertEquals(2, action.sessionIndex());
    }

    @Test
    void parsesAddAction() throws Exception {
        String json = """
                {
                  "actions": [
                    {
                      "type": "ADD",
                      "subject": "Chemistry",
                      "title": "Lab review",
                      "focusType": "DEEP",
                      "date": "2026-11-20",
                      "start": "14:00",
                      "durationMinutes": 50,
                      "priority": "HIGH"
                    }
                  ],
                  "summary": "Added Chemistry session."
                }
                """;
        FocusEditResult result = parser.parse(json, TODAY);

        assertFalse(result.isEmpty());
        assertEquals(1, result.actions().size());
        FocusEditAction action = result.actions().get(0);
        assertEquals(FocusEditAction.Type.ADD, action.type());
        assertEquals("Chemistry", action.subject());
        assertEquals("Lab review", action.title());
        assertEquals(FocusType.DEEP, action.focusType());
        assertEquals(LocalDate.of(2026, 11, 20), action.date());
        assertEquals(LocalTime.of(14, 0), action.start());
        assertEquals(50, action.durationMinutes());
        assertEquals(Priority.HIGH, action.priority());
    }

    @Test
    void parsesSpecificEditActions() throws Exception {
        String json = """
                {
                  "actions": [
                    {"type": "MOVE", "sessionIndex": 1, "date": "2026-11-20", "start": "19:00"},
                    {"type": "RESIZE", "sessionIndex": 2, "durationMinutes": 75},
                    {"type": "CHANGE_SUBJECT", "sessionIndex": 3, "subject": "Physics",
                     "title": "Review chapter 4"},
                    {"type": "SET_WINDOW", "earliest": "18:00", "latest": "22:00"}
                  ],
                  "summary": "Adjusted the plan."
                }
                """;
        FocusEditResult result = parser.parse(json, TODAY);

        assertEquals(4, result.actions().size());
        FocusEditAction move = result.actions().get(0);
        assertEquals(FocusEditAction.Type.MOVE, move.type());
        assertEquals(LocalDate.of(2026, 11, 20), move.date());
        assertEquals(LocalTime.of(19, 0), move.start());

        FocusEditAction resize = result.actions().get(1);
        assertEquals(FocusEditAction.Type.RESIZE, resize.type());
        assertEquals(75, resize.durationMinutes());

        FocusEditAction change = result.actions().get(2);
        assertEquals(FocusEditAction.Type.CHANGE_SUBJECT, change.type());
        assertEquals("Physics", change.subject());
        assertEquals("Review chapter 4", change.title());

        FocusEditAction window = result.actions().get(3);
        assertEquals(FocusEditAction.Type.SET_WINDOW, window.type());
        assertEquals(LocalTime.of(18, 0), window.earliest());
        assertEquals(LocalTime.of(22, 0), window.latest());
    }

    @Test
    void parsesModesBreaksLimitsAndTaskActions() throws Exception {
        FocusEditResult result = parser.parse("""
                {"actions":[
                  {"type":"CHANGE_TYPE","sessionIndex":1,"focusType":"EXAM"},
                  {"type":"SET_BREAK","durationMinutes":15},
                  {"type":"SET_SESSION_LIMIT","durationMinutes":60},
                  {"type":"ADD_TASK","subject":"Chemistry","title":"Review acids",
                   "focusType":"QUICK","date":"2026-11-20","start":"18:00",
                   "durationMinutes":25},
                  {"type":"REMOVE_TASK","subject":"History"},
                  {"type":"PRIORITIZE_SUBJECT","subject":"Math","priority":"HIGH"}
                ],"summary":"Updated study settings and tasks."}
                """, TODAY);

        assertEquals(6, result.actions().size());
        assertEquals(FocusType.EXAM, result.actions().get(0).focusType());
        assertEquals(FocusEditAction.Type.SET_BREAK, result.actions().get(1).type());
        assertEquals(FocusEditAction.Type.ADD_TASK, result.actions().get(3).type());
        assertEquals(FocusEditAction.Type.REMOVE_TASK, result.actions().get(4).type());
        assertEquals(FocusEditAction.Type.PRIORITIZE_SUBJECT,
                result.actions().get(5).type());
    }

    @Test
    void skipsSpecificActionsMissingRequiredFields() throws Exception {
        String json = """
                {
                  "actions": [
                    {"type": "MOVE", "sessionIndex": 1},
                    {"type": "RESIZE", "sessionIndex": 1},
                    {"type": "CHANGE_SUBJECT", "sessionIndex": 1},
                    {"type": "SET_WINDOW", "earliest": "18:00"},
                    {"type": "ADD", "subject": "Math"}
                  ],
                  "summary": "Nothing valid."
                }
                """;
        FocusEditResult result = parser.parse(json, TODAY);

        assertTrue(result.isEmpty());
        assertTrue(result.summary().contains("Skipped"),
                "Skipped actions should be explained in the summary");
    }

    @Test
    void parsesMarkdownFencedJson() throws Exception {
        String json = """
                ```json
                {
                  "actions": [
                    {
                      "type": "UPDATE",
                      "sessionIndex": 3,
                      "start": "20:00"
                    }
                  ],
                  "summary": "Rescheduled session 3."
                }
                ```
                """;
        FocusEditResult result = parser.parse(json, TODAY);

        assertFalse(result.isEmpty());
        assertEquals(1, result.actions().size());
        assertEquals(LocalTime.of(20, 0), result.actions().get(0).start());
    }

    @Test
    void rejectsEmptyOrNonJsonResponse() {
        assertThrows(FocusPlanningException.class, () -> parser.parse("", TODAY));
        assertThrows(FocusPlanningException.class, () -> parser.parse("Not a JSON", TODAY));
    }
}
