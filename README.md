# Puppy AI

A desktop study companion built with **Java 25 and JavaFX**. A pixel-art puppy
lives on the desktop and provides local/cloud AI chat, document-based help,
Focus planning, interactive quizzes and a Pomodoro timer. Everything runs in
one Java process, with embedded SQLite for local storage.

**Current-state recap: 19 November 2026.** The features below are implemented;
unimplemented capabilities and outstanding release work are grouped under
[Future Scope](#future-scope). The detailed delivery ledger and acceptance
criteria are in [Implementation Status](docs/IMPLEMENTATION_STATUS.md).

## Implemented features

| Area | Available now |
| --- | --- |
| Desktop mascot | Transparent, draggable puppy; six animations; click/wake messages; idle sleep and periodic notifications |
| Independent windows | Cached Chat, Study, Focus and Settings windows; navigation without closing other features; draggable headers; Chat expand/restore |
| AI chat | Streaming Markdown replies through Ollama or OpenRouter, model switching, conversation history, copy/reuse and visible request errors |
| Intent routing | AI-backed Chat/Quiz dispatch using current-conversation evidence, with clarification and missing-source responses |
| Documents | Pinned PDF, DOCX, DOC, TXT and Markdown text for Chat, Focus extraction and quizzes |
| Current time | Read-only computer-clock tool with local date, time, weekday, timezone and UTC offset |
| Focus Planner | AI fact extraction, deterministic scheduling from now, manual classes/tasks/sessions, editing, completion and session countdowns |
| Multiple Quiz | Inline multiple-choice cards, checking, explanations, scoring, review and retry; reusable saved questions |
| Study | 25-minute focus / 5-minute break Pomodoro with start, pause, resume, reset and completion feedback |
| Settings | Live model selection, masked OpenRouter key management, always-on-top and saved supplemental chat instructions |
| Persistence | SQLite-backed chats, extracted attachments, Focus state, settings and quiz definitions; save retry and startup compatibility handling |

## Requirements and quick start

| Requirement | Version / purpose |
| --- | --- |
| JDK | 25 |
| Maven | 3.9+ |
| Ollama | 0.35+ for local model requests |
| OpenRouter key | Required only for cloud model requests |

Set `JAVA_HOME` to JDK 25 if Maven selects another installation. On Windows,
from PowerShell in the project directory:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn clean compile
mvn javafx:run
```

Alternatively, run the Windows launcher, which compiles before launching:

```powershell
.\run.bat
```

Manual planning/editing and the Study timer do not require a model. Chat,
intent routing, quiz generation and Focus document/description extraction use
the selected model and need its provider to be available.

## Models and configuration

### Available models

| Model | Provider | Requirement |
| --- | --- | --- |
| Gemini 2.5 Flash | OpenRouter | API key and internet connection |
| Gemini 2.5 Flash-Lite | OpenRouter | API key and internet connection |
| Gemini 2.5 Pro | OpenRouter | API key and internet connection |
| Qwen3.5 9B, configurable | Ollama | Running Ollama server with the model installed |

Open **More > Settings** to switch models. All choices remain visible even
before a cloud key is configured. With no valid saved choice, Puppy defaults
to Gemini 2.5 Flash when a key exists, otherwise the configured local model.
A saved selection takes precedence. Provider failures produce visible errors;
they do not silently switch to another model.

### Local Ollama setup

Install [Ollama](https://ollama.com/) and download the default model:

```powershell
ollama pull hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M
ollama serve
```

Run `ollama serve` only if the server is not already running. Select the local
model in Settings. The default endpoint is `http://localhost:11434`; the model
is downloaded separately, not bundled with Puppy.

### OpenRouter key

1. Open **More > Settings** and select a cloud model.
2. Paste a key into the masked **OpenRouter API key** field below the picker.
3. Choose **Save key**. Model changes apply to subsequent requests.

The key is stored separately from the database in
`%LOCALAPPDATA%\PuppyAI\ai-secrets.properties`. Removing it leaves the cloud
model selected so another key can be entered. A key supplied externally takes
precedence and makes the Settings field read-only.

### Configuration overrides

JVM properties take precedence over the corresponding environment settings.
Puppy reads environment variables and properties directly.

| Setting | Environment variable | JVM property | Default |
| --- | --- | --- | --- |
| Local endpoint | `PUPPY_OLLAMA_URL` | `puppy.ollama.url` | `http://localhost:11434` |
| Local model | `PUPPY_OLLAMA_MODEL` | `puppy.ollama.model` | `hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M` |
| Cloud endpoint | `PUPPY_OPENROUTER_URL` | `puppy.openrouter.url` | `https://openrouter.ai/api/v1` |
| Cloud key | `PUPPY_OPENROUTER_API_KEY` or `OPENROUTER_API_KEY` | `puppy.openrouter.apiKey` | Saved key, if present |
| Data directory | `PUPPY_DATA_DIRECTORY` | `puppy.dataDirectory` | Per-user PuppyAI directory |
| Always on top | `PUPPY_ALWAYSONTOP` | `puppy.alwaysOnTop` | Saved preference, otherwise `true` |

For example, set session-local overrides before launching:

```powershell
$env:PUPPY_OLLAMA_URL = 'http://localhost:11434'
$env:PUPPY_OLLAMA_MODEL = 'qwen2.5:7b'
mvn javafx:run
```

The Settings label follows the configured local model. Changing its model name
changes its selection ID; an obsolete saved selection returns to the normal
default selection. The legacy `ai.properties` model choice is imported once
into SQLite without deleting the original file.

## Desktop controls and windows

| Action | Result |
| --- | --- |
| Drag the puppy | Move the desktop mascot |
| Click the puppy | Show a random message, or wake it from sleep |
| Double-click the puppy | Toggle the compact Study / Focus / Chat / More menu |
| Choose a feature | Open or bring forward its cached window without closing other features |
| Menu inside a feature | Open navigation without hiding that feature |
| Drag a feature header | Move that window; controls do not initiate dragging |
| Expand / Restore in Chat | Resize Chat to the monitor workspace or its normal size |
| Click outside / Escape | Dismiss the compact main menu |
| `_` in a feature or More/About | Hide only that window; background generation/timers continue |
| X in a feature or More/About | Hide only that window; closing Chat also cancels pending generation |
| More > Quit Puppy | Drain saves, close windows and shut down services |
| Right-click the mascot | Development menu for state previews and quitting |

The compact main menu has no minimize/close buttons and also dismisses on
focus loss or a second mascot double-click. More/About and feature windows
remain open when another application receives focus. Always-on-top applies to
the mascot, menus and all feature windows.

Chat, Study, Focus and Settings can remain open together. Hidden windows retain
drafts, position, Chat expansion, running timers and quiz attempts during the
current app session. Reopening a feature brings back the same window without
recentering it. The mascot stays in its own window throughout.

After five idle minutes Puppy sleeps; idle notifications may appear every two
minutes. Active study timers prevent idle sleep. Chat Thinking/Talking takes
priority during replies, then Puppy returns to Studying while either the Study
or Focus timer is running. The two timers operate independently, even hidden.

## Chat and conversation context

- Replies stream as Markdown with headings, emphasis, lists, quotes, code,
  links and tables. UI token updates are batched, and completed replies show
  elapsed generation time.
- The composer offers **Chat / Focus plan / Multiple Quiz / Model / Help**.
  `/focus`, `/quiz`, `/model` and `/help` provide keyboard alternatives.
  Model and Help respond locally.
- Chat and Multiple Quiz requests pass through model-backed intent resolution.
  An explicit quiz request can create a quiz even in Chat mode; selecting
  Multiple Quiz is a preference, not an instruction to turn every greeting or
  capability question into a quiz.
- Routing uses bounded current-conversation history and available attachment
  evidence. It can recover relevant earlier topics or ask for clarification or
  missing sources. It does not access other conversations.
- Regular replies use bounded recent history plus selected relevant evidence.
  Normal history is limited to ten messages / 8,000 characters; Chat document
  context is capped at 20,000 characters. Routing can inspect up to 80 history
  messages / 24,000 characters. Saved history is not a promise of unlimited
  model memory.
- Right-click messages to **Copy message** or **Reuse in message box**. User
  bubbles also have visible Copy/Reuse actions. Reuse fills the composer without
  sending, and Ctrl+V pastes normally.
- Conversations and their attachment context survive restart. The last active
  chat reopens; new messages move that conversation to the top of history,
  while merely switching conversations does not change activity order.
- New conversations receive a provisional title and one separate model request
  to paraphrase the first request without attachments. Failure retains the
  provisional title and exposes a nonblocking warning.
- Cancellation, interrupted replies, save failures and provider/routing errors
  are surfaced. Failed saves retain content in memory with **Retry save**.

Routing, title generation and feature-specific generation can add model calls
beyond the final reply. Cloud requests can incur provider charges; local
requests require the Ollama server and configured model.

### Supplemental instructions

In **More > Settings > More instructions**, enter style preferences such as
"Use simple explanations and examples", then choose **Save instructions**.

- The editor enforces a live 4,000-Unicode-character limit without silently
  truncating oversized input.
- **Reset to default** clears the draft; Save commits that reset. Unsaved
  drafts survive hiding/reopening Settings during the current app session.
- Saved preferences apply to subsequent ordinary chat replies through either
  provider. A submitted request keeps its own instruction snapshot.
- Preferences supplement the fixed persona and document-handling rules; they
  do not modify Focus extraction, Quiz JSON contracts, title generation or
  canned mascot messages.
- Save failure preserves the draft and the previously committed preferences.
  The Settings header stays fixed while its body scrolls.

### Current-time tool

`get_current_time` is a read-only application tool backed by the computer's
clock. The app invokes it immediately before each ordinary reply and Focus
extraction, providing a fresh local timestamp, weekday, timezone and UTC offset
to either provider. It is application-dispatched context, not an autonomous
model-controlled tool loop.

Ask "What time is it?" or "Tell me the current time in 12-hour format." The
model formats the result in the conversation language and requested format;
the default is 24-hour time with the timezone. Time is sampled after queued
work rather than reused from earlier chat messages. Accuracy depends on the
computer clock; the clock read itself needs no network, but the reply still
uses the selected model.

## Document attachments

Use the pin button to attach up to **five documents per conversation**. Each
file is limited to **20 MiB** and extraction to **80,000 characters**; downstream
prompts have smaller budgets and may omit/truncate text.

| Format | Extraction |
| --- | --- |
| PDF | Apache PDFBox, with page markers for citations |
| DOCX | Apache POI paragraphs and tables |
| DOC | Apache POI Scratchpad for legacy Word documents |
| TXT / MD | UTF-8 text through Java NIO |

Unreadable, password-protected/encrypted, oversized and image-only PDFs produce
visible errors. Answers based on document facts are instructed to cite the
filename and available page markers; model output still requires checking.

Only extracted text and filename/page/truncation metadata are saved, not
original file copies or absolute paths. Moving the original file does not
break restored context. Removing an attachment chip removes its stored context
from that conversation; historical filename labels remain. New chats start
without attachments.

## Focus Planner

Open **Focus** from the menu for manual planning, or select **Focus plan** in
Chat with pinned files and/or a typed description. Both use the same persisted
planner state.

### Generation flow

1. The app extracts document text and includes the typed request. Focus model
   context is capped at 24,000 document characters.
2. The selected model extracts classes, tasks and subjects as strict JSON using
   the current-time context. It does not choose session times.
3. An explicit free-time range, such as `18:00-21:30`, `7h-9h` or `7pm-9pm`,
   overrides the saved daily window. Otherwise Puppy immediately uses saved
   availability, default **08:00-22:00**, without a follow-up question.
   Timetable class times are not treated as availability.
4. The deterministic Java planner samples time again after extraction and
   schedules from **now, rounded up to a five-minute boundary**. It uses later
   availability or a later day when needed, subject to deadlines and the horizon.
5. Chat summarizes the result and opens Focus Planner when sessions were created.

Describe important/difficult subjects, low confidence, assignments, programming,
projects or exam preparation for **Deep** work; light review, reading, notes
and flashcards use **Quick** work.

| Type | Typical block |
| --- | --- |
| Quick | 25 minutes |
| Deep | 50 minutes |

Long tasks split into equal blocks: 120 minutes of Deep work becomes three
40-minute sessions. A subject/timetable without explicit tasks gets review
tasks; imports merge classes/tasks and ignore exact duplicates.

### Scheduling and manual sessions

- Sessions stay inside daily availability and avoid weekly classes.
- Work respects deadlines; overdue or unplaceable work produces warnings.
- Ten-minute gaps separate sessions; each subject gets at most two sessions
  per day, with subjects scheduled round-robin.
- The default horizon is one week, extended by a later task deadline. An
  explicit duration or end date overrides it, capped at 365 days. Examples:
  `in 3 days`, `for 2 weeks`, `1 month`, `deadline 20/11`.
- Durations count today inclusively; dates without a year use the next
  non-past occurrence. Explicit dates win over durations. A requested plan end
  applies to one generation, not all subsequent plans.
- The panel supports manual classes, tasks and sessions. Sessions can be edited,
  removed, completed, or run with Start/Pause/Resume and Finish/Done controls.
- Manual placement validates dates, availability, classes and other sessions.
  Editing preserves completion and ignores the session's own old slot when
  checking overlap; Cancel edit leaves it unchanged.
- **Generate plan** rebuilds the session list from classes/tasks, replacing
  existing generated and hand-made sessions. Saved historical sessions and
  completion otherwise survive restart; countdowns do not resume after restart.

## Multiple Quiz

Describe a topic, refer to current-conversation evidence or attach documents,
then choose **Multiple Quiz** and Send. An explicit creation request in Chat
can also route here. Example: "Create six questions about fractions: two Easy,
two Medium and two Hard."

- Supports **1-20 questions**, default **five Medium**, with four distinct
  options and one correct answer per question. Questions follow the request
  language; controls use English.
- The router can supply topic/count/difficulty/source evidence. Otherwise a
  structured specification request precedes question generation. Strict JSON
  validation checks the shape, count, difficulty and source-citation requirements.
- Required documents/discussion must be available; missing evidence leads to a
  clarification/error rather than invented source content. General-knowledge
  generation is allowed when no particular source is required.
- Independent quiz cards live inside Chat. Select an option and **Check answer**
  to lock it and reveal feedback/explanation. Previous/Next only navigate.
- **Finish** shows correct, incorrect and skipped totals. Finishing with
  unchecked questions requires confirmation; those questions score zero.
- **Review** is read-only. **Retry quiz** starts a new attempt on the same
  questions without a model call, with confirmation before discarding answers.
  **Back to chat** focuses the composer.
- Generated definitions and their chat entries save atomically before answering
  is enabled. Generation errors offer **Retry generation**; storage errors retain
  validated questions for **Retry save** without another generation call.
- Cancel, switching conversations or closing Chat cancels pending generation.
  Hiding Chat or opening another feature lets it continue in the background.
- Definitions survive restart. Selections, scores, progress and review state are
  intentionally memory-only and reset on restart; switching chats preserves
  them while the app runs. Normal Chat remains available after generation.

Questions/explanations use the existing Markdown renderer; options are plain
text. Structural validation cannot prove factual accuracy or correct grounding.

## Study timer

The Study window provides a **25-minute focus / 5-minute break** Pomodoro.
Start/Pause/Resume preserves the remaining phase time; Reset returns to a fresh
focus phase. Finishing a phase prepares the next phase for manual start and
shows completion feedback. The panel mascot idles when paused/reset, studies
while running and briefly celebrates completion.

The countdown derives remaining time from a monotonic target timestamp instead
of counting UI ticks. Study and Focus countdowns stay independent and continue
while their windows are hidden. Timer state is kept only for the running app.

## Local storage, privacy and recovery

Data lives in `%LOCALAPPDATA%\PuppyAI` on Windows, or
`~/.local/share/PuppyAI` when `LOCALAPPDATA` is unavailable. Use a local disk;
only one running instance may own a data directory.

| Data | Storage / lifetime |
| --- | --- |
| Conversations, messages, timings and extracted attachments | `puppy.db`, persists across restarts |
| Focus classes/tasks/preferences/plan/session completion | `puppy.db`, persists across restarts |
| Model selection, always-on-top and additional instructions | `puppy.db`, persists across restarts |
| Quiz definitions and question content | `puppy.db`, persists across restarts |
| OpenRouter key saved in Settings | Separate `ai-secrets.properties` |
| Quiz attempts, running timers, window layout and unsaved drafts | Current app session only |

SQLite uses serialized worker-thread operations, transactions, foreign keys,
bounded busy waits, rollback journaling and a per-directory process lock.
Changes publish only after their saves commit. Greeting-only chats are not saved.
Interrupted replies retain their status, and quitting drains accepted writes
with retry or explicit quit-without-saving on failure. Forced termination can
lose streamed text since the most recent save.

Database failures block startup instead of resetting data. Schema upgrades
create `puppy.pre-upgrade.db`; newer schemas are rejected. Focus loading also
transactionally removes the known retired preference/control fields that caused
older snapshots to fail restoration, preserving supported fields and rejecting
unknown incompatible data. Back up the data directory before manual recovery;
do not delete the database merely to dismiss a startup error.

Local data, extracted text, additional instructions and the saved key are
**not encrypted at rest**. Local inference keeps supplied content on the local
Ollama endpoint; cloud requests send their conversation/document context to
OpenRouter and its routed provider. Keys use the authorization header and are
redacted from provider-facing error messages. SQLite `secure_delete` is best
effort, not a physical-erasure guarantee for SSDs, snapshots or backups.

## Architecture and repository layout

One Java process connects JavaFX presentation to application services through
method calls. Controllers call services; services own provider, extraction and
repository work. Background operations stay off the JavaFX Application Thread;
UI updates are marshalled back to it.

| Layer | Main components |
| --- | --- |
| Presentation | Mascot/Chat/Focus/Study/Settings controllers, FXML, CSS, `QuizCard`, `MarkdownRenderer` |
| Services | `ChatService`, `IntentResolutionService`, `ModelService`, `FocusService`, `FocusPlanner`, `QuizService`, `StudyTimer`, `CurrentTimeTool` |
| State and persistence | `MascotStateService`, `ChatHistoryService`, `AppSettingsService`, domain records, SQLite repositories |
| Infrastructure | Ollama/OpenRouter HTTP clients, PDFBox/POI extraction, `Database`, `AiSettingsStore`, `ThreadManager` |
| Bootstrap | `PuppyApplication`, `AppConfig`, `ServiceRegistry` |

```text
pom.xml                         Maven dependencies, Java 25 and JavaFX launch
run.bat                         Windows compile-and-launch helper
docs/IMPLEMENTATION_STATUS.md    Delivery ledger and verification details
src/main/java/com/puppy/
  ai/                           Provider clients and model catalog
  config/                       Configuration, model/key storage and wiring
  controller/                   JavaFX feature controllers
  database/                     SQLite lifecycle and schema
  document/                     Local text extraction
  enums/                        Shared state/type enums
  model/                        Chat, Focus, storage and quiz domain types
  repository/                   Settings, Chat, Focus and Quiz persistence
  service/                      Chat/routing/planning/quizzes/timers/time tool
  ui/                           Mascot rendering, Markdown and quiz cards
  util/                         Thread and duration helpers
src/main/resources/
  fxml/                         Shell, Chat, Study, Focus and Settings layouts
  css/                          JavaFX styling
  icons/                        Window/taskbar icon
  sprites/                      Numbered transparent PNG frames by state
src/test/java/com/puppy/         Unit, persistence, HTTP-stub and JavaFX tests
```

The mascot uses numbered PNG frames (`0.png`, `1.png`, ...) loaded once by
`MascotAnimator`, with metadata in `MascotSprites`. IDLE has three frames;
HAPPY, THINKING, TALKING, STUDYING and SLEEPING have two each. Per-frame timing
is 2,000 ms except THINKING (500 ms) and SLEEPING (1,000 ms).

### Verification

```powershell
mvn test
```

Tests cover provider HTTP handling, bounded prompts and routing contracts,
document extraction, deterministic scheduling, timers, quiz interactions,
SQLite persistence/migration/rollback and JavaFX layout/window/controller flows.
Time-tool coverage includes both providers, timezone/date rollover, queued
replies, slow extraction and midnight scheduling boundaries.

HTTP stubs and fixed clocks verify contracts, not live-model factual quality or
intent accuracy. The delivery ledger distinguishes automated results from
live-provider/manual release verification.

## Future Scope

The following are **not implemented**. Deferred ideas are not delivery promises;
some exclusions, especially quiz-attempt persistence, are deliberate product
decisions rather than unfinished parts of current features.

### Research and additional tools

- Web browsing/research, Tavily integration and automatic research routing.
- General model-controlled tool calling beyond the app-dispatched current-time
  context; tools to inspect planner/timer state or execute confirmed actions.
- Calendar integration, reminders and notifications scheduled from AI requests.
- Search/retrieval over full attached documents beyond bounded prompt context.
- OCR for scanned/image-only documents.

### Planning and learning extensions

- Study presets such as 50/10 and user-configurable durations in the UI.
- Resume/recover countdowns after restarting the app.
- Preserve hand-made sessions during plan regeneration or link sessions to
  source tasks.
- Richer calendars for exam weeks, one-off timetable exceptions and multi-week
  recurring courses.
- Automatic Focus-to-Quiz integration, quiz timers, image questions and a
  dedicated equation renderer.
- Persist quiz attempts, answers, progress or scores; these are intentionally
  excluded from the current storage design.

### Storage, desktop and distribution

- Chat-history search, export, pagination and retention-management UI.
- Accounts, cloud sync and database/credential encryption at rest.
- Automatic backup restoration/recovery UI and `.env` loading.
- System tray integration and true hide-to-tray with a reliable restore path.
- Per-pixel mouse hit testing: transparent parts of the mascot's rectangular
  JavaFX window currently receive mouse input.
- Persist window geometry/layout across restarts, a first-run walkthrough and
  expanded keyboard-navigation UX.
- Packaged installers/bundled runtime, release optimization and broader
  platform/distribution validation.

### Outstanding release verification

- Live Ollama/OpenRouter smoke checks for routing, time-answer formatting,
  document-grounded replies and quiz accuracy/languages/difficulty boundaries.
- Manual desktop/restart checks for long Markdown, small windows, independent
  feature windows, retry flows, confirmations and keyboard interaction.
- Final release sign-off and any fixes identified by those checks, recorded in
  [Implementation Status](docs/IMPLEMENTATION_STATUS.md).
