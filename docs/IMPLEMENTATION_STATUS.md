# Puppy AI — Implementation Status

Last updated: 2026-11-17

## Milestones

| Milestone | Scope | Status |
| --- | --- | --- |
| 1 | Project foundation and JavaFX desktop shell | ✅ Complete |
| 2 | Frontend interactions and UI | ✅ Complete |
| 3 | Pure Java services and SQLite persistence | Planned — see `docs/MILESTONE_3_PLAN.md` |
| 4 | AI integration (local Ollama + cloud OpenRouter chat, document attachments, Focus planner) | 🟡 In progress |
| 5 | Internet research and extraction | Pending |
| 6 | Study mode, settings, integration | Pending |
| 7 | Testing, optimization, packaging | Pending |

## Completed (milestone 1)

- Maven project: JDK 25 target, JavaFX 25.0.5, JavaFX Maven Plugin, JUnit 5.14.4.
- `PuppyApplication` entry point with frameless (`StageStyle.TRANSPARENT`),
  transparent, always-on-top window positioned in the bottom-right screen corner.
- Draggable mascot with clean shutdown through `Application.stop()`.
- Six mascot states (`MascotState`) driven by `DefaultMascotStateService`.
- `MascotAnimator` plays separately loaded PNG frames through an ImageView,
  with per-state frame timing and a global speed multiplier.
- `ServiceRegistry` builds and owns services; `ThreadManager` provides a daemon
  worker pool for later milestones and is closed on exit.
- Right-click menu to switch animations and quit (the window has no title bar).
- Separate numbered state frames at `resources/sprites/`, icon at
  `resources/icons/`.
- Tests: frame resource/count/timing contract and mascot state service behaviour.

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

- Fixed bottom-right mascot window with separate menu and feature-panel windows.
  Study opens top-right and Chat opens centered on the primary screen; opening
  or resizing panels does not move the mascot.
- Cached Chat, Study and Settings views plus More menu, Escape dismissal,
  close/minimize behavior and a right-click development fallback.
- Chat uses mock replies on the shared worker pool, rejects blank input and
  offers new conversations with session-only history.
- Study UI drives the existing 25/5 timer with start/pause/reset, phase labels
  and completion feedback.
- Settings exposes the functional in-session always-on-top switch. Model and
  OpenRouter key controls were added later in the AI milestone below.
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
- `SettingsController` — initially showed the configured Ollama model and server
  alongside the always-on-top switch; the cloud-models milestone below extended it
  with the model picker, cloud/local status and OpenRouter key section.
- Presentation is unchanged: the mascot still follows THINKING → TALKING →
  HAPPY → IDLE while a reply is generated.
- Tests: `OllamaClientTest` (streaming, errors, availability),
  `DocumentTextExtractorTest` (PDF, DOCX, text, limits),
  `ChatPromptBuilderTest` and `ChatServiceTest`.

## Completed (cloud models and local key storage)

- **Provider abstraction:** `ChatProvider` defines streaming chat and JSON
  extraction. `OllamaClient` and `OpenRouterClient` implement it, and
  `ModelService` owns the active selection and routes every request. Controllers
  still never touch a provider directly.
- **OpenRouter client:** streams `POST /chat/completions` as server-sent events
  with `stream: true`, sends `Authorization: Bearer <key>`, and forwards only
  assistant content fragments (private reasoning is ignored). JSON extraction
  uses the same endpoint without streaming and requests
  `response_format: {"type":"json_object"}`. Authentication, insufficient
  credits, rate limits, missing models, network, timeout and safety failures
  have friendly messages; the key is redacted from error text.
- **Model catalog:** OpenRouter Gemini 2.5 Flash, Gemini 2.5 Flash-Lite, Gemini
  2.5 Pro and the configured local Qwen model. All four stay listed even before
  a key exists so the picker can lead the user to the key field. With no
  explicit choice Puppy prefers Gemini 2.5 Flash for speed when a key exists and
  otherwise uses the local model; choosing the local model is persisted and
  honoured on later launches. Selecting a cloud model without a key is allowed
  and keeps the choice so the key can be entered next.
- **Local key storage:** `AiSettingsStore` writes the model choice to
  `%LOCALAPPDATA%\PuppyAI\ai.properties` and the key to
  `%LOCALAPPDATA%\PuppyAI\ai-secrets.properties`. Writes are atomic and
  owner-only permissions are applied where the platform supports POSIX. A key
  in `OPENROUTER_API_KEY`, `PUPPY_OPENROUTER_API_KEY` or
  `-Dpuppy.openrouter.apiKey` wins over a saved key and makes the Settings field
  read-only. Keys never enter the repository, tests, docs or logs.
- **Settings UI:** live model picker with `OpenRouter · <model>` and
  `<model> · local` labels, cloud/local pill, model detail and hint, and a key
  section shown directly below the picker only for an OpenRouter model. The
  section contains a masked `PasswordField`, Save key / Remove key, a
  configured/not-configured pill, environment-managed state and action status.
  The local model section stays visible, needs no key, and hides the key section
  from the layout. The Settings window recomputes its height when the section
  appears or hides so nothing clips.
- **Chat and Focus:** the Model reply names the selected provider and whether it
  is cloud or local. Errors distinguish a missing or rejected key, insufficient
  credits, rate limits, blocked prompts and network failures.
  `AiFocusPlanExtractor` asks the selected model for strict JSON, so Focus
  planning works through OpenRouter or Ollama while `FocusPlanner` still makes
  every scheduling decision.
- **Tests:** `OpenRouterClientTest` covers SSE streaming, header-only keys, JSON
  mode, blocked prompts, redacted errors and unreachable hosts.
  `AiSettingsStoreTest` covers persistence, separation of selection and secret
  files, validation, removal and damaged files. `ModelServiceTest` covers
  cloud-first defaults, explicit local choices, selecting a cloud model without
  a key, clearing and restoring the key, switching and environment-key
  precedence. `FxmlLayoutTest` verifies the picker labels, masked key field,
  key-section visibility for cloud versus local, and Settings fit. The suite is
  109 tests, 0 failures.

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
- **Chat size:** the chat window opens centered at up to 940 × 720 and has an
  Expand / Restore toggle for the primary screen's visual workspace.
- **Markdown:** `MarkdownRenderer` renders headings, emphasis, strikethrough,
  inline/fenced code, lists, quotes, links, tables and rules as JavaFX nodes.
  Streaming text is plain for speed; the completed message is re-rendered as
  Markdown.
- **Sessions:** the history sidebar is scrollable and highlights the active
  session. Streaming tokens update a single label on an 80 ms UI ticker; the
  session snapshot is updated when a message completes or the user switches
  conversations, avoiding a full history copy per token.
- **Reply timing:** each finished model reply shows a small **Completed in:**
  footer under the bubble. `DurationFormats` switches from seconds to
  minutes/seconds to hours/minutes, and the measured time is stored with the
  message so it survives history switches.
- **Copy and reuse:** every message bubble has a right-click **Copy message** /
  **Reuse in message box** context menu. User messages also show visible
  **Copy** and **Reuse** actions under the bubble; Copy briefly changes to
  “Copied”, and Reuse restores the exact request to the composer without sending
  it. Normal clipboard paste (**Ctrl+V**) remains available in the message box.
- **Commands:** an option beside the message box replaces typed slash commands:
  **Chat**, **Focus plan**, **Model** and **Help**. Model and Help answer
  locally. The 📌 button attaches documents, and the new-chat greeting ends at
  “Ask me anything.” Focus has no Quick/Deep selector; Puppy infers the type
  from the description and asks for a free-time window when one is missing.
- **Tests:** `MascotMessagesTest`, Markdown rendering and composer-option
  assertions in `FxmlLayoutTest`, the updated greeting contract in
  `ChatServiceTest`, and `DurationFormatsTest` for reply timing.

## Completed (Focus planner)

- **Domain model:** `FocusType` (Quick 25 min / Deep 50 min), `Priority`,
  `ClassSlot`, `StudyTask`, `FocusPreferences`, `FocusSession`, `FocusPlan`,
  `FocusPlannerInput` and `FocusExtraction`. Sessions carry subject, date,
  start/end, type and a completion flag. Priority stays internal for ordering
  and is not exposed in the UI.
- **Deterministic planning:** `FocusPlanner` schedules only inside the daily free
  window (default 08:00–22:00), subtracts weekly class times, respects
  deadlines, leaves 10-minute gaps, caps each subject at two sessions per day,
  and round-robins subjects for fairness. The horizon defaults to one week, uses
  the latest task deadline when it is later, and honors an explicit plan end
  such as `trong 3 ngày`, `2 tuần`, `1 tháng`, `deadline 20/11` or `trước
  2026-12-01`; the horizon is capped at 365 days. Today's first session starts at
  least 15 minutes after the current time.
- **Block sizing:** long tasks are split into equal blocks rather than one full
  block plus a remainder; a 120-minute deep task becomes 3 × 40 minutes.
- **Tolerant parsing:** `FocusFormats` reads `18:00`, `6h30`, `6h30 chieu`,
  ISO and `20/11/2026` dates, English/Vietnamese weekdays, Quick/Deep keywords
  (including importance, difficulty, extra time and low confidence → Deep),
  priorities, durations such as `1h30`, `1.5h` and `45 phút`, and free-time
  windows such as `18:00-21:30`, `7h tối - 9h tối` and `7pm-9pm`. Date-like
  number pairs are rejected so a deadline cannot become a study window.
  `parsePlanEnd` reads inclusive day/week/month durations, deadline/appointment
  dates, and `hôm nay` / `ngày mai`; weekday phrases such as `thứ 2 tuần sau`
  and time-only text are ignored. Dates without a year use the next non-past
  occurrence, and explicit dates win over duration text.
- **Model extraction:** `AiFocusPlanExtractor` asks the selected model for
  strict JSON through `ChatProvider.jsonChat` (Ollama `format: json`, OpenRouter
  `response_format: json_object`, low temperature). `FocusJsonParser`
  accepts fenced/prefaced JSON, skips invalid rows with notes, and creates
  review tasks when the text lists only subjects.
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
  Planner. When the description has no free-time window, Puppy stores the request
  and any requested plan end, asks “When are you free?”, parses the reply into
  `FocusPreferences`, and then builds the plan. A duration or deadline in the
  request sets the plan end; otherwise the planner uses one week. Timetable class
  times are not treated as free time. Slash commands remain a keyboard fallback,
  and the composer placeholder is “Ask me anything”.
- **Navigation:** a Focus button in the main menu and a Focus planner item in
  More; the Focus panel is cached, monitor-aware (980 × 840 preferred, clamped
  to the screen), and a running Focus timer blocks the idle sleep timer.
- **Layout fix:** FXML multi-class attributes use commas
  (`styleClass="panel-card, focus-panel"`); space-separated values were parsed as
  one CSS class, so panel styling had not been applied.
- **Tests:** `FocusPlannerTest` (class/free-time conflicts, deadlines, subject
  fairness, per-day caps, block sizes, warnings, the one-week default, the
  latest-deadline horizon and explicit plan ends), `FocusFormatsTest` (time,
  date, type, priority, duration, free-time-window and plan-end parsing),
  `FocusJsonParserTest` (strict/fenced/Vietnamese input, invalid rows, subject
  fallback) and `FocusServiceTest` (merge, override, review fallback, one-shot
  plan end, manual validation, session editing and completion).
  `OllamaClientTest` now checks JSON-mode requests, and `FxmlLayoutTest` opens
  the Focus panel and checks the visible Copy/Reuse actions and right-click
  message menu.

## Services used by the UI

| Class | How the UI should use it |
| --- | --- |
| `ChatService` | `ChatController` gets it from `ServiceRegistry`, streams replies with `reply(...)`, and reads attachments with `readDocument(...)`. It delegates to the selected provider through `ModelService`. |
| `ModelService` | Owns the model catalog, cloud-first selection, persisted choice, key state and request routing. `SettingsController` calls it to switch models and manage the OpenRouter key. |
| `FocusService` | `FocusController` gets it from `ServiceRegistry`; the Chat focus option calls `importDocuments(...)` on the worker pool. Shares classes, tasks, preferences, plan and session state with the panel. |
| `FocusPlanner` | Pure-Java scheduling owned by `FocusService`; the UI never makes placement decisions. |
| `FocusPlanExtractor` | Infrastructure behind `FocusService`; `AiFocusPlanExtractor` reads files through the selected model, while tests use a fake extractor. |
| `DocumentTextExtractor` | Used through `ChatService.readDocument(...)` and `FocusService.importFromFiles(...)`; never called from the JavaFX thread. |
| `ChatProvider` | Contract implemented by `OllamaClient` and `OpenRouterClient`; `ModelService` constructs and routes to it. Controllers never construct a provider. |
| `OllamaClient` | Local `/api/chat` streaming, JSON mode, availability checks and friendly errors. |
| `OpenRouterClient` | Cloud SSE streaming, JSON mode, bearer header, redacted errors and OpenRouter-specific friendly messages. |
| `AiSettingsStore` | Persists the model choice and OpenRouter key in the local data directory; never used by controllers directly. |
| `StudyTimer` | `StudyController` owns the JavaFX ticker and calls `start()` / `pause()` / `reset()` and `completePhase()` when a phase finishes. |
| `MascotStateService` | The single place that changes the mascot animation; listeners marshal to the JavaFX thread. |
| `MockChatResponder` | Legacy stand-in retained for its unit test; it is no longer connected to the UI. |
| `AIMode` | Reserved; current selection and routing live in `ModelService`. |

## Current implementation notes

- **JDK:** `pom.xml` targets Java 25, matching the Java 25 LTS runtime and
  JavaFX 25.0.5.
- **Local AI:** `AppConfig` reads `puppy.ollama.url` / `PUPPY_OLLAMA_URL`
  (default `http://localhost:11434`) and `puppy.ollama.model` /
  `PUPPY_OLLAMA_MODEL` (default `hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M`).
  `OllamaClient` sends `think: false` because the chat UI shows the answer
  rather than Qwen's private reasoning.
- **Cloud AI:** `AppConfig` reads `OPENROUTER_API_KEY`, `PUPPY_OPENROUTER_API_KEY`
  or `-Dpuppy.openrouter.apiKey` and the endpoint from `PUPPY_OPENROUTER_URL` /
  `-Dpuppy.openrouter.url` (default `https://openrouter.ai/api/v1`).
  A key saved in Settings lives in `%LOCALAPPDATA%\PuppyAI\ai-secrets.properties`.
  OpenRouter requests use the `Authorization: Bearer` header, never the URL or
  body.
- **Prompt:** Puppy's rules and optional document context are merged into one
  system message, which both providers accept as a normal system message.
  `ChatPromptBuilder` keeps at most ten turns, 8,000 characters of history and
  20,000 characters of document text; the local model runs with a
  16,384-token context window.
- **Attachments:** only extracted text is kept in memory. With a local model it
  stays on this computer; with a cloud model it is sent to OpenRouter and its
  model provider with the prompt. Up to five files can be attached per
  conversation; the attachment bar restores when an archived conversation is
  selected. Removing a chip removes its context from later prompts.
- **Sprites:** numbered transparent PNG frames are stored separately by state.
  `MascotSprites` defines their order and per-image timing; the animator loads
  each image once and switches images during playback.
- **State priority:** `DefaultMascotStateService` currently lets the latest
  request win; the priority rules (study must not be interrupted by an idle
  refresh) belong to milestone 6.
- **Data directory:** `AppConfig` resolves `%LOCALAPPDATA%\PuppyAI` (or
  `~/.local/share/PuppyAI`). `AiSettingsStore` writes the model choice and
  OpenRouter key there; no runtime data is written inside the project.
- **Focus pipeline:** the selected model only extracts facts from timetable/task
  files; `FocusPlanner` makes every scheduling decision. This keeps the hard
  constraints deterministic and testable when the model is unavailable. Import
  reads up to five files (20 MB each) and caps combined document context at
  24,000 characters; the planner defaults to a one-week horizon and accepts an
  explicit duration or deadline, capped at 365 days.
- **Focus fallback:** a timetable without tasks gets one "Review class notes"
  Quick task per subject, due at the end of the default week (today + 6 days).
  Imports merge into the existing class/task set and exact duplicates are
  ignored.
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
  sessions) are session-only; SQLite persistence is a later milestone. The model
  choice and OpenRouter key do persist in the local data directory.
- Regenerating a Focus plan replaces the scheduled session list, so hand-made
  sessions are lost unless they are recreated. Manual and generated sessions are
  not yet linked to source tasks.
- Focus extraction requires the selected model: a working OpenRouter key and
  internet connection for cloud, or Ollama and the configured model for local.
  Combined document text is capped at 24,000 characters, and scanned/image-only
  files are not supported.
- The planner uses weekly class slots and a one-week default horizon (or the
  user's explicit end); it does not model exam weeks, one-off timetable changes
  or multi-week recurring courses.
- Scanned/image-only PDFs have no selectable text and are rejected. Password-
  protected files and files above 20 MB are also rejected.
- Very long documents are truncated to fit the model context, so the answer may
  miss details near the end of a large file.
- Cloud OpenRouter chat and local Ollama chat are wired up; Tavily research and
  the automatic research routing are still pending. An invalid, underfunded or
  rate-limited OpenRouter key falls back to a friendly chat error rather than
  switching models automatically.
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
4. Mascot frame images are loaded once and reused during playback; state changes
   do not reload image resources.
5. Background work is confined to the `ThreadManager` pool; UI updates are
   marshalled with `Platform.runLater`. Streamed tokens are batched so a fast
   model cannot flood the JavaFX Application Thread.
6. Long-running timers calculate the deadline once (`StudyTimer`) instead of
   decrementing a counter, so UI stalls or a slow tick cannot make them drift.
7. `ChatController` calls `ChatService`; it never talks to Ollama, OpenRouter,
   PDFBox or Apache POI directly.
8. Local inference uses Ollama's HTTP API and cloud inference uses OpenRouter's
   HTTPS API through the same `ChatProvider` contract. No Python service or
   separate backend is introduced.
9. Document context and chat history are bounded before every request so a large
   attachment cannot overflow the selected model's context window or block the
   UI.
10. Puppy's rules and document context share one starting system message, which
    both Ollama and OpenRouter accept as a normal system message.
11. Only extracted document text is held in memory. Original files stay where
    the user chose them. That text is sent to OpenRouter and its model provider
    only while a cloud model is selected; the local model keeps it on this
    computer.
12. The OpenRouter key is stored outside the repository in the user's data
    directory and is sent only in the `Authorization: Bearer` header. Error text
    is redacted and the UI never displays the key.
