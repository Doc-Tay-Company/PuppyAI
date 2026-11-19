package com.puppy.service;

import com.puppy.ai.AiException;
import com.puppy.model.ChatMessage;
import com.puppy.model.FocusContext;
import com.puppy.model.FocusEditResult;
import com.puppy.model.FocusPlan;
import com.puppy.model.FocusPreferences;
import com.puppy.model.FocusSession;
import com.puppy.model.ClassSlot;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Asks the selected AI model to interpret an edit request against the current
 * Study plan and return structured JSON actions.
 *
 * <p>The model receives the full plan context (numbered session list, classes,
 * preferences) and the user's natural-language request. It returns a JSON
 * object with an {@code actions} array and a {@code summary} string. The
 * {@link FocusPlanEditParser} validates and converts the JSON into
 * {@link FocusEditResult} actions that the caller applies through
 * {@link FocusService}.</p>
 *
 * <p>This class does not modify the plan itself; it is a pure AI bridge. All
 * scheduling constraints are enforced by {@link FocusService} when the caller
 * applies each action.</p>
 */
public final class AiFocusPlanEditor {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_DISPLAY =
            DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private static final String SYSTEM_PROMPT = """
            You are Puppy's Study editor. The user already has a study plan with
            scheduled study sessions. They want to change it.

            You will receive:
            1. The current numbered session list
            2. The user's weekly class schedule (sessions must not overlap these)
            3. The user's free-time window
            4. The user's edit request in their language

            Return ONLY a JSON object with this exact shape:
            {
              "actions": [
                {"type": "MOVE", "sessionIndex": 1, "date": "2026-11-20", "start": "19:00"},
                {"type": "RESIZE", "sessionIndex": 2, "durationMinutes": 75},
                {"type": "REMOVE", "sessionIndex": 3},
                {"type": "CHANGE_SUBJECT", "sessionIndex": 4, "subject": "Physics",
                 "title": "Review chapter 4"},
                {"type": "ADD", "subject": "Chemistry", "title": "Lab review",
                 "date": "2026-11-21", "start": "14:00",
                 "durationMinutes": 50, "priority": "MEDIUM"},
                {"type": "SET_WINDOW", "earliest": "18:00", "latest": "22:00"},
                {"type": "SET_BREAK", "durationMinutes": 15},
                {"type": "SET_SESSION_LIMIT", "durationMinutes": 60},
                {"type": "ADD_TASK", "subject": "Chemistry", "title": "Review acids",
                 "date": "2026-11-22", "start": "18:00",
                 "durationMinutes": 25, "priority": "MEDIUM"},
                {"type": "REMOVE_TASK", "subject": "History", "title": "Read chapter 2"},
                {"type": "PRIORITIZE_SUBJECT", "subject": "Math", "priority": "HIGH"}
              ],
              "summary": "Moved the Math session to 19:00."
            }

            Rules:
            - Use MOVE to change a session's date and/or start time.
            - Rearrange session order with the minimum set of MOVE actions; preserve every
              unaffected session and never overlap the moved sessions.
            - Use RESIZE to change only a session's duration.
            - Use REMOVE to delete a session.
            - Use CHANGE_SUBJECT to rename the subject; include "title" only when the
              title should change too.
            - Use ADD for a new session with subject, title, date, start and duration.
            - Use SET_WINDOW only when the user asks to change their free time. Include
              both "earliest" and "latest". All sessions must still fit inside it.
            - Use SET_BREAK to change the break between generated sessions.
            - Use SET_SESSION_LIMIT to cap the length of one study session.
            - Do not classify sessions by focus type. Do not return "focusType" or CHANGE_TYPE.
            - For a requested total study time, resize only pending sessions so their
              combined duration matches the request. Preserve completed/skipped history.
            - Use ADD_TASK to add durable task data plus its first scheduled session.
              Include subject, title, date, start, duration and priority.
            - Use REMOVE_TASK to remove a task and its pending sessions. A subject with
              no title removes all its pending tasks; completed/skipped history stays.
            - Use PRIORITIZE_SUBJECT to change the priority of that subject's tasks and
              sessions without changing unrelated work.
            - UPDATE is accepted as a general patch, but prefer the specific action.
            - "sessionIndex" is the 1-based number from the session list. Required for
              every session action; ignored for ADD and SET_WINDOW.
            - "sessionIndex" MUST strictly be one of the numbers from the list above (1 to N).
              NEVER invent a sessionIndex that is not present in the list.
            - NEVER confuse calendar dates or day numbers (e.g. "ngày 25", "the 25th", "25 days")
              with "sessionIndex". A day of the month like "25" is a "date" (e.g. "2026-11-25"),
              NOT sessionIndex 25.
            - Minimal Disruption Principle: ONLY modify the session(s) explicitly mentioned
              by the user. NEVER delete, remove, or truncate other sessions or other days unless
              the user explicitly demands deleting them. Do NOT compress or collapse the multi-day
              plan into a single day.
            - No Month Leaps: Keep all date modifications within the existing plan's dates or
              the current month unless the user explicitly requested a specific different month or date.
            - Include only the fields that change. Omit unchanged fields.
            - Times use 24-hour HH:mm. Dates use yyyy-MM-dd.
            - Resolve relative dates (tomorrow, next Monday, thứ 4) against today's date
              supplied below.
            - Do not move completed sessions unless the user explicitly asks.
            - Do not schedule sessions that overlap classes.
            - Do not schedule outside the free-time window.
            - "summary" is a short human-readable description of what you changed,
              in the same language the user writes in (Vietnamese if the user writes in Vietnamese).
            - If the user's request is unclear, return an empty actions array and put
              a clarifying question in "summary".
            - No Markdown fences, no explanation, no text outside the JSON.
            """.strip();

    private final ModelService modelService;
    private final Clock clock;

    public AiFocusPlanEditor(ModelService modelService) {
        this(modelService, Clock.systemDefaultZone());
    }

    public AiFocusPlanEditor(ModelService modelService, Clock clock) {
        this.modelService = Objects.requireNonNull(modelService, "modelService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Sends the current plan and the user's edit request to the AI model and
     * returns parsed edit actions.
     *
     * @param context     the current study plan, classes and preferences
     * @param classes     the user's weekly class schedule
     * @param preferences the user's free-time window
     * @param userRequest the natural-language edit request
     * @return parsed edit actions and a summary for the user
     * @throws FocusPlanningException when the model is unreachable or returns
     *                                 unparseable output
     */
    public FocusEditResult edit(FocusContext context, String userRequest)
            throws FocusPlanningException {
        Objects.requireNonNull(context, "context");
        if (userRequest == null || userRequest.isBlank()) {
            throw new FocusPlanningException("Tell me what you want to change in the plan.");
        }

        LocalDate today = LocalDate.now(clock);
        String prompt = buildPrompt(context, userRequest, today);

        List<ChatMessage> messages = List.of(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user(prompt));
        try {
            String reply = modelService.jsonChat(messages);
            return new FocusPlanEditParser().parse(reply, today);
        } catch (AiException e) {
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? "I couldn't reach the AI model to edit the plan."
                    : e.getMessage();
            throw new FocusPlanningException(message, e);
        }
    }

    /** Compatibility overload for callers that already have the three parts. */
    public FocusEditResult edit(FocusPlan plan, List<ClassSlot> classes,
                                FocusPreferences preferences, String userRequest)
            throws FocusPlanningException {
        return edit(new FocusContext(plan, classes, preferences), userRequest);
    }

    private static String buildPrompt(FocusContext context, String userRequest,
                                      LocalDate today) {
        FocusPlan plan = context.plan();
        FocusPreferences preferences = context.preferences();
        StringBuilder prompt = new StringBuilder();

        // Date context
        prompt.append("Today is ").append(ISO.format(today))
                .append(" (").append(today.getDayOfWeek()
                        .getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .append(").\n\n");

        // Free-time window
        prompt.append("Free-time window: ")
                .append(TIME.format(preferences.earliest()))
                .append(" - ").append(TIME.format(preferences.latest()))
                .append(context.preferencesConfigured()
                        ? "\n\n" : " (default; the user has not chosen one yet)\n\n");
        prompt.append("Break between sessions: ")
                .append(preferences.breakMinutes()).append(" minutes\n")
                .append("Maximum session length: ")
                .append(preferences.maxSessionMinutes()).append(" minutes\n\n");

        // Class schedule
        List<ClassSlot> classes = context.classes();
        if (!classes.isEmpty()) {
            prompt.append("Weekly classes (sessions must NOT overlap these):\n");
            for (ClassSlot slot : classes) {
                prompt.append("- ").append(slot.subject()).append(": ")
                        .append(slot.day().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                        .append(" ").append(TIME.format(slot.start()))
                        .append("-").append(TIME.format(slot.end())).append("\n");
            }
            prompt.append("\n");
        }

        // Current sessions (numbered)
        List<FocusSession> sessions = plan.sessions();
        if (sessions.isEmpty()) {
            prompt.append("Current plan: (no sessions)\n\n");
        } else {
            prompt.append("Current plan sessions:\n");
            for (int i = 0; i < sessions.size(); i++) {
                FocusSession session = sessions.get(i);
                prompt.append(i + 1).append(". ")
                        .append(DATE_DISPLAY.format(session.date()))
                        .append(" ").append(TIME.format(session.start()))
                        .append("-").append(TIME.format(session.end()))
                        .append(" | ").append(session.subject())
                        .append(": ").append(session.title())
                        .append(" (").append(session.durationMinutes()).append("m")
                        .append(", ").append(session.state().name().toLowerCase(Locale.ROOT))
                        .append(")\n");
            }
            prompt.append("\n");
        }

        // User request
        prompt.append("User's edit request:\n").append(userRequest);

        return prompt.toString();
    }
}
