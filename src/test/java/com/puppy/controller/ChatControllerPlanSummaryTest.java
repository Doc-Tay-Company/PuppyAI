package com.puppy.controller;

import com.puppy.enums.FocusType;
import com.puppy.enums.Priority;
import com.puppy.model.FocusPlan;
import com.puppy.model.FocusSession;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatControllerPlanSummaryTest {

    @Test
    void listsEverySessionSoTheWholePlanCanBeReviewed() {
        List<FocusSession> sessions = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            sessions.add(new FocusSession("s" + i, "Math", "Session " + (i + 1),
                    FocusType.QUICK, LocalDate.of(2026, 11, 16).plusDays(i / 2),
                    LocalTime.of(18, 0), 25, Priority.MEDIUM, false));
        }
        FocusPlan plan = new FocusPlan(sessions, List.of(), LocalDate.of(2026, 11, 16));

        String summary = ChatController.focusPlanSummary(plan);

        for (FocusSession session : sessions) {
            assertTrue(summary.contains(session.title()),
                    "Every session should be visible before saving: " + session.title());
        }
        assertFalse(summary.contains("more in"),
                "The preview must not replace the rest of the plan with a truncation note");
        assertTrue(summary.contains("12 sessions"));
        assertTrue(summary.contains("Open Study"));
    }
}
