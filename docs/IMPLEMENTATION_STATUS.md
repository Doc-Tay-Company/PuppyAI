# Puppy AI — Implementation Status

Last updated: 2026-11-16

## Milestones

| Milestone | Scope | Status |
| --- | --- | --- |
| 1 | Project foundation and JavaFX desktop shell | ✅ Complete |
| 2 | Frontend interactions and UI | ✅ Complete |
| 3 | Pure Java services and SQLite persistence | Planned — see `docs/MILESTONE_3_PLAN.md` |
| 4 | AI integration (local Ollama chat, document attachments, Focus planner; cloud pending) | 🟡 In progress |
| 5 | Internet research and extraction | Pending |
| 6 | Study mode, settings, integration | Pending |
| 7 | Testing, optimization, packaging | Pending |

## Completed (milestone 1)

- Maven project: JDK 25 target, JavaFX 25.0.5, JavaFX Maven Plugin, JUnit 5.14.4.
- `PuppyApplication` entry point with frameless (`StageStyle.TRANSPARENT`),
  transparent, always-on-top window positioned in the bottom-right screen corner.
- Draggable mascot with clean shutdown through `Application.stop()`.
- Six mascot states (`MascotState`) driven by `DefaultMascotStateService`.
- `MascotAnimator` plays the supplied 6 × 6 spritesheet through an ImageView
  viewport, with per-state frame timing and a global speed multiplier.
- `ServiceRegistry` builds and owns services; `ThreadManager` provides a daemon
  worker pool for later milestones and is closed on exit.
- Right-click menu to switch animations and quit (the window has no title bar).
- Placeholder-friendly assets: spritesheet at `resources/sprites/`, icon at
  `resources/icons/`.
- Tests: spritesheet grid/artwork contract and mascot state service behaviour.

## Completed (milestone 2)

- `StudyTimer` — pure-Java Pomodoro countdown: 25:00 focus / 5:00 break, plus
  one-minute test durations. Remaining time is calculated from a target
  timestamp (not tick counts), supports start/pause/reset and phase completion,
  and takes an injectable nano clock so it is tested without waiting.
- `MockChatResponder` — deterministic canned replies (greeting, study and help
  cues, rotating fallbacks) for the chat UI until the real AI lands. Exposed as
  `ServiceRegistry.mockChatResponder()`.
- `AIMode` enum (`ONLINE`, `OFFLINE`, `AUTO`) backing the future settings panel.
- Tests: `StudyTimerTest` (11 cases, fake clock) and `MockChatResponderTest`.

- Expandable JavaFX shell with hover greeting, happy hover reaction,
  drag-safe double-click menu and anchored resize behavior.
- Cached Chat, Study and Settings panels plus More menu, Escape dismissal,
  collapse/minimize behavior and a right-click development fallback.
- Chat uses mock replies on the shared worker pool, rejects blank input and
  offers new conversations with session-only history.
- Study UI drives the existing 25/5 timer with start/pause/reset, phase labels
  and completion feedback.
- Settings exposes the functional in-session always-on-top switch and clearly
  marks AI provider controls as future work; no credentials are collected.
- `FxmlLayoutTest` loads all layouts and verifies the drag-safe menu, Study /
  Chat / Settings navigation, panel collapse, and blank-chat rejection.

## Completed (local AI chat and document attachments)

- `OllamaClient` — streams `POST /api/chat` as newline-delimited JSON, with
  `think: false` for Qwen3.5, a 16,384-token context, friendly errors for a
  stopped server, a missing model, timeouts and interrupted replies.
- `ChatService` + `ChatPromptBuilder` — one system prompt with Puppy's rules,
  optional document context and the ten most recent turns. Document text is
  capped at 20,000 characters and history at 8,000 characters.
- `DocumentTextExtractor` — PDF via PDFBox (with `--- Page N ---` markers),
  DOCX/DOC via Apache POI, plus TXT/Markdown. Files are limited to 20 MB and
  80,000 extracted characters; scanned PDFs and encrypted files get a clear
  message instead of a stack trace.
- `ChatController` — streams tokens into the chat bubble, batches UI updates,
  cancels generation when the conversation changes or the panel closes, and
  keeps up to five document attachments for the current conversation. Attachment
  chips can be removed, and switching back to an archived conversation restores
  its attachments.
- `SettingsController` — shows the configured Ollama model and server alongside
  the always-on-top switch.
- Presentation is unchanged: the mascot still follows THINKING → TALKING →
  HAPPY → IDLE while a reply is generated.
- Tests: `OllamaClientTest` (streaming, errors, availability),
  `DocumentTextExtractorTest` (PDF, DOCX, text, limits),
  `ChatPromptBuilderTest` and `ChatServiceTest`.

## Completed (mascot and chat polish)

- **Navigation:** Chat, Study, Focus, Settings and submenus now have a Menu /
  Back to menu action. Panel close and minimize still collapse to the desktop
  mascot.
- **Hover and click:** the old hover bubble is gone. The mascot uses a hand
  cursor on hover and shows a random message from `MascotMessages` on click;
  single clicks wait briefly so double-click still opens the menu.
- **Sleep:** after five minutes without app activity (and with no open panel,
  menu or running Study/Focus timer) the mascot enters `SLEEPING`; clicking it
  wakes the mascot, shows a wake line and restarts the inactivity timer.
- **Notifications:** while the mascot is idle and compact, a quiet random
  notification appears every two minutes and auto-hides after eight seconds.
- **Chat size:** the chat panel opens at a monitor-aware 940 × 720 maximum,
  stays anchored bottom-right and has an Expand / Restore toggle for the full
  visual workspace.
- **Markdown:** `MarkdownRenderer` renders headings, emphasis, strikethrough,
  inline/fenced code, lists, quotes, links, tables and rules as JavaFX nodes.
  Streaming text is plain for speed; the completed message is re-rendered as
  Markdown.
- **Sessions:** the history sidebar is scrollable and highlights the active
  session. Streaming tokens update a single label on an 80 ms UI ticker; the
  session snapshot is updated when a message completes or the user switches
  conversations, avoiding a full history copy per token.
- **Commands:** an option beside the message box replaces typed slash commands:
  **Chat**, **Focus plan**, **Model** and **Help**. Model and Help answer
  locally. The 📌 button attaches documents, and the new-chat greeting ends at
  “Ask me anything.” Focus has no Quick/Deep selector; Puppy infers the type
  from the description and asks for a free-time window when one is missing.
- **Tests:** `MascotMessagesTest`, Markdown rendering and composer-option
  assertions in `FxmlLayoutTest`, and the updated greeting contract in
  `ChatServiceTest`.

## Completed (Focus planner)

- **Domain model:** `FocusType` (Quick 25 min / Deep 50 min), `Priority`,
  `ClassSlot`, `StudyTask`, `FocusPreferences`, `FocusSession`, `FocusPlan`,
  `FocusPlannerInput` and `FocusExtraction`. Sessions carry subject, date,
  start/end, type and a completion flag. Priority stays internal for ordering
  and is not exposed in the UI.
- **Deterministic planning:** `FocusPlanner` schedules only inside the daily free
  window (default 08:00–22:00), subtracts weekly class times, respects
  deadlines, leaves 10-minute gaps, caps each subject at two sessions per day,
  round-robins subjects for fairness and plans at most 21 days ahead. Today's
  first session starts at least 15 minutes after the current time.
- **Block sizing:** long tasks are split into equal blocks rather than one full
  block plus a remainder; a 120-minute deep task becomes 3 × 40 minutes.
- **Tolerant parsing:** `FocusFormats` reads `18:00`, `6h30`, `6h30 chieu`,
  ISO and `20/11/2026` dates, English/Vietnamese weekdays, Quick/Deep keywords
  (including importance, difficulty, extra time and low confidence → Deep),
  priorities, durations such as `1h30`, `1.5h` and `45 phút`, and free-time
  windows such as `18:00-21:30`, `7h tối - 9h tối` and `7pm-9pm`. Date-like
  number pairs are rejected so a deadline cannot become a study window.
- **Model extraction:** `OllamaFocusPlanExtractor` asks the local model for
  strict JSON via the new `OllamaClient.jsonChat` (`format: json`, low
  temperature). `FocusJsonParser` accepts fenced/prefaced JSON, skips invalid
  rows with notes, and creates review tasks when the text lists only subjects.
- **Application service:** `FocusService` owns classes, tasks, preferences and
  the current plan; it merges imports, deduplicates facts, adds the timetable
  review fallback, validates hand-made and edited sessions, and
  completes/removes sessions. Manual tasks and sessions default to the internal
  medium priority.
- **Focus Planner panel:** `focus.fxml` + `FocusController` provide file import,
  free-time selectors, Classes/Tasks/Session tabs, a date-grouped session list
  with Quick/Deep badges, a live `StudyTimer` countdown, and
  Start/Pause/Finish/Remove/Clear controls. Priority is not shown. Each session
  has an **Edit** action that opens the Session tab, and saving re-validates
  free time, classes and same-day session overlaps while preserving completion;
  **Cancel edit** restores the add-session form.
- **Chat focus option:** **Focus plan** accepts pinned files or a typed subject
  description, infers Quick/Deep from the description, runs extraction on the
  shared worker pool, replies with a Markdown plan summary and opens Focus
  Planner. When the description has no free-time window, Puppy stores the request,
  asks “When are you free?”, parses the reply into `FocusPreferences`, and then
  builds the plan. Timetable class times are not treated as free time. Slash
  commands remain a keyboard fallback, and the composer placeholder is “Ask me
  anything”.
- **Navigation:** a Focus button in the main menu and a Focus planner item in
  More; the Focus panel is cached, monitor-aware (980 × 840 preferred, clamped
  to the screen), and a running Focus timer blocks the idle sleep timer.
- **Layout fix:** FXML multi-class attributes use commas
  (`styleClass="panel-card, focus-panel"`); space-separated values were parsed as
  one CSS class, so panel styling had not been applied.
- **Tests:** `FocusPlannerTest` (class/free-time conflicts, deadlines, subject
  fairness, per-day caps, block sizes, warnings), `FocusFormatsTest` (time,
  date, type, priority, duration and free-time-window parsing),
  `FocusJsonParserTest` (strict/fenced/Vietnamese input, invalid rows, subject
  fallback) and `FocusServiceTest` (merge, override, review fallback, manual
  validation, session editing and completion). `OllamaClientTest` now checks
  JSON-mode requests, and `FxmlLayoutTest` opens the Focus panel.

## Services used by the UI

| Class | How the UI should use it |
| --- | --- |
| `ChatService` | `ChatController` gets it from `ServiceRegistry`, streams replies with `reply(...)`, and reads attachments with `readDocument(...)`. |
| `FocusService` | `FocusController` gets it from `ServiceRegistry`; the Chat focus option calls `importDocuments(...)` on the worker pool. Shares classes, tasks, preferences, plan and session state with the panel. |
| `FocusPlanner` | Pure-Java scheduling owned by `FocusService`; the UI never makes placement decisions. |
| `FocusPlanExtractor` | Infrastructure behind `FocusService`; `OllamaFocusPlanExtractor` reads files, while tests use a fake extractor. |
| `DocumentTextExtractor` | Used through `ChatService.readDocument(...)` and `FocusService.importFromFiles(...)`; never called from the JavaFX thread. |
| `OllamaClient` | Infrastructure owned by `ChatService`; controllers never construct it. |
| `StudyTimer` | `StudyController` owns the JavaFX ticker and calls `start()` / `pause()` / `reset()` and `completePhase()` when a phase finishes. |
| `MascotStateService` | The single place that changes the mascot animation; listeners marshal to the JavaFX thread. |
| `MockChatResponder` | Legacy stand-in retained for its unit test; it is no longer connected to the UI. |
| `AIMode` | Reserved for the future cloud routing/settings work. |

## Current implementation notes

- **JDK:** `pom.xml` targets Java 25, matching the Java 25 LTS runtime and
  JavaFX 25.0.5.
- **Local AI:** `AppConfig` reads `puppy.ollama.url` / `PUPPY_OLLAMA_URL`
  (default `http://localhost:11434`) and `puppy.ollama.model` /
  `PUPPY_OLLAMA_MODEL` (default `hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M`).
  `OllamaClient` sends `think: false` because the chat UI shows the answer
  rather than Qwen's private reasoning.
- **Prompt:** Qwen's chat template requires a single system message at the
  start, so Puppy's rules and the optional document context are merged into one
  system message. `ChatPromptBuilder` keeps at most ten turns, 8,000 characters
  of history and 20,000 characters of document text; the model runs with a
  16,384-token context window.
- **Attachments:** only extracted text is kept in memory and sent to the local
  model. Up to five files can be attached per conversation; the attachment bar
  restores when an archived conversation is selected. Removing a chip removes
  its context from later prompts.
- **Sprites:** the sheet is a 6 × 6 grid (row per state, 6 frames per row,
  768 × 768 with 128 × 128 px cells today). Frame size is derived at runtime, so
  new art with the same grid shape requires no code change.
  `docs/SPRITESHEET_GUIDE.md` specifies that grid and ships a trace template at
  `docs/spritesheet-guide.png`.
- **State priority:** `DefaultMascotStateService` currently lets the latest
  request win; the priority rules (study must not be interrupted by an idle
  refresh) belong to milestone 6.
- **Data directory:** `AppConfig` resolves `%LOCALAPPDATA%\PuppyAI` (or
  `~/.local/share/PuppyAI`) but nothing writes to it yet.
- **Focus pipeline:** the local model only extracts facts from timetable/task
  files; `FocusPlanner` makes every scheduling decision. This keeps the hard
  constraints deterministic and testable when the model is unavailable. Import
  reads up to five files (20 MB each) and caps combined document context at
  24,000 characters; the planner horizon is 21 days.
- **Focus fallback:** a timetable without tasks gets one "Review class notes"
  Quick task per subject, due in seven days. Imports merge into the existing
  class/task set and exact duplicates are ignored.
- **Focus sessions:** sessions are in memory only. `Generate plan` rebuilds the
  scheduled list from classes and tasks; hand-made sessions are not carried into
  a regenerated plan. Manual and generated sessions can be edited in the Session
  tab, with placement re-validated on save. A running Focus countdown keeps the
  mascot in STUDYING and prevents the idle-sleep timer from firing.

## Known bugs / limitations

- The previous mascot sheet was not anchored to a common centre and ground line,
  and its effects crossed cell borders, so animations shook and the TALKING row
  showed paws from the row above. The current sheet follows the anchor rules in
  `docs/SPRITESHEET_GUIDE.md`; the animator cuts exact cells either way.
- The entire 336 × 252 window captures mouse input, including transparent
  pixels; per-pixel hit testing is not part of the MVP.
- Chat history, attachments and all Focus data (classes, tasks, preferences and
  sessions) are session-only; SQLite persistence is a later milestone.
- Regenerating a Focus plan replaces the scheduled session list, so hand-made
  sessions are lost unless they are recreated. Manual and generated sessions are
  not yet linked to source tasks.
- Focus extraction requires Ollama and the configured model. Combined document
  text is capped at 24,000 characters, and scanned/image-only files are not
  supported.
- The planner uses weekly class slots and a 21-day horizon; it does not model
  exam weeks, one-off timetable changes or multi-week recurring courses.
- Scanned/image-only PDFs have no selectable text and are rejected. Password-
  protected files and files above 20 MB are also rejected.
- Very long documents are truncated to fit the model context, so the answer may
  miss details near the end of a large file.
- Only local Ollama chat is wired up; cloud AI, Tavily research and automatic
  routing are still pending.
- True hide-to-tray is deferred until a reliable restore path is implemented.
- PDFBox/POI print a one-time "Log4j API could not find a logging provider"
  warning in the console because the project does not bundle a logging backend;
  it does not affect chat or extraction.

## Follow-on UI ideas

- Add study presets (e.g. 25/5 and 50/10), with custom durations later.
- Add persistent/searchable chat history after SQLite persistence is ready.
- Add persistent Focus plans and link manual sessions to their source tasks so a
  regenerated plan can keep them.
- Add true hide-to-tray only with a reliable restore path.
- Add keyboard navigation and a first-run interaction walkthrough.

## Architectural decisions

1. Single Java process: JavaFX and services communicate through Java interfaces,
   with no internal REST layer.
2. Controllers receive services from `ServiceRegistry`; they never construct
   infrastructure themselves.
3. The mascot's visual state is application state, owned by
   `MascotStateService`, not by animation callbacks — this prevents flicker
   while tokens stream.
4. Animations are played by moving an ImageView viewport over one loaded sheet,
   which avoids per-frame image loading and keeps switching cheap.
5. Background work is confined to the `ThreadManager` pool; UI updates are
   marshalled with `Platform.runLater`. Streamed tokens are batched so a fast
   model cannot flood the JavaFX Application Thread.
6. Long-running timers calculate the deadline once (`StudyTimer`) instead of
   decrementing a counter, so UI stalls or a slow tick cannot make them drift.
7. `ChatController` calls `ChatService`; it never talks to Ollama, PDFBox or
   Apache POI directly.
8. The local model is accessed over Ollama's HTTP API. No Python service or
   separate backend is introduced.
9. Document context and chat history are bounded before every request so a large
   attachment cannot overflow Qwen's context window or block the UI.
10. Qwen's chat template accepts only one starting system message, so the
    document context is appended to Puppy's system prompt instead of being sent
    as a second system message.
11. Only extracted document text is held in memory. Original files stay where
    the user chose them, and nothing is uploaded to a cloud service.
