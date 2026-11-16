# Puppy AI — Implementation Status

Last updated: 2026-11-15

## Milestones

| Milestone | Scope | Status |
| --- | --- | --- |
| 1 | Project foundation and JavaFX desktop shell | ✅ Complete |
| 2 | Frontend interactions and UI | ✅ Complete |
| 3 | Pure Java services and SQLite persistence | Planned — see `docs/MILESTONE_3_PLAN.md` |
| 4 | AI integration (local Ollama chat + document attachments; cloud pending) | 🟡 In progress |
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

- **Navigation:** Chat, Study, Settings and submenus now have a Menu / Back to
  menu action. Panel close and minimize still collapse to the desktop mascot.
- **Hover and click:** the old hover bubble is gone. The mascot uses a hand
  cursor on hover and shows a random message from `MascotMessages` on click;
  single clicks wait briefly so double-click still opens the menu.
- **Sleep:** after five minutes without app activity (and with no open panel,
  menu or running study timer) the mascot enters `SLEEPING`; clicking it wakes
  the mascot, shows a wake line and restarts the inactivity timer.
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
- **Commands:** `/model` reports the exact local model and Ollama server;
  `/help` lists commands. The new-chat greeting no longer names the model.
- **Tests:** `MascotMessagesTest`, Markdown rendering and `/model` assertions in
  `FxmlLayoutTest`, and the updated greeting contract in `ChatServiceTest`.

## Services used by the UI

| Class | How the UI should use it |
| --- | --- |
| `ChatService` | `ChatController` gets it from `ServiceRegistry`, streams replies with `reply(...)`, and reads attachments with `readDocument(...)`. |
| `DocumentTextExtractor` | Used through `ChatService.readDocument(...)`; never called from the JavaFX thread. |
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

## Known bugs / limitations

- The previous mascot sheet was not anchored to a common centre and ground line,
  and its effects crossed cell borders, so animations shook and the TALKING row
  showed paws from the row above. The current sheet follows the anchor rules in
  `docs/SPRITESHEET_GUIDE.md`; the animator cuts exact cells either way.
- The entire 336 × 252 window captures mouse input, including transparent
  pixels; per-pixel hit testing is not part of the MVP.
- Chat history and attachments are session-only; SQLite persistence is a later
  milestone.
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
