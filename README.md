# Puppy AI

A desktop AI companion: a pixel-art puppy that lives on your desktop, chats with
you, researches the web and runs study sessions. JavaFX frontend with pure Java
services in a single desktop process — no Python, no separate server.

**Status: local AI chat and Focus planning are wired up.** The app launches as a
frameless, transparent, always-on-top puppy. Click the puppy for a random
message, double-click to open Study / Focus / Chat / More, and the shell expands
around the mascot while keeping its screen anchor. After five idle minutes the
puppy falls asleep; click it to wake it up. Chat streams Markdown replies from
the local model through **Ollama**, offers **Focus plan / Model / Help** next to
the message box, and can read pinned PDF, Word and text files into the
conversation. Focus planning reads a pinned timetable or subject file, or a
typed subject description, infers Quick or Deep focus from how you describe the
subject, asks for your free-time window when it is missing, and builds a
constraint-aware study plan while the Focus Planner panel supports hand-made
classes, tasks and sessions. Chat, focus data and attachments are session-only;
cloud AI and persistence are future work.

## Requirements

| Tool | Version |
| --- | --- |
| JDK | 25 |
| Maven | 3.9+ |
| Ollama | 0.35+ for local chat |

Because Maven may pick a different JDK than your `PATH`, point `JAVA_HOME` at
your JDK 25 installation before building:

```bash
export JAVA_HOME="C:/path/to/jdk-25"   # Git Bash
# or: set JAVA_HOME=C:\path\to\jdk-25  # cmd.exe
```

## Local AI setup (Ollama + Qwen3.5 9B)

Puppy does not bundle a model. Install [Ollama](https://ollama.com/), then make
sure the local model is available:

```bash
ollama pull hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M
ollama serve
```

The default configuration uses:

| Setting | Default |
| --- | --- |
| Ollama URL | `http://localhost:11434` |
| Model | `hf.co/unsloth/Qwen3.5-9B-GGUF:Q4_K_M` |

Override either value with an environment variable or a system property:

```bash
set PUPPY_OLLAMA_MODEL=qwen2.5:7b
set PUPPY_OLLAMA_URL=http://localhost:11434
# or: -Dpuppy.ollama.model=qwen2.5:7b
```

Puppy reads environment variables and JVM system properties; it does not parse
a `.env` file automatically.

If Ollama is not running or the model is missing, the chat panel shows a
friendly message with the command to fix it — it does not crash.

## Build and run

```bash
mvn clean compile     # compile
mvn test              # run unit tests
mvn javafx:run        # launch Puppy
```

## Controls

| Action | Result |
| --- | --- |
| Drag with left mouse button | Move the puppy around the desktop |
| Click the puppy | Show a random, friendly message |
| Double-click the puppy | Open or close the Study / Focus / Chat / More menu |
| Idle for five minutes | Puppy falls asleep; click it to wake it up |
| Every two minutes | Puppy may show a quiet notification while idle |
| Study / Focus / Chat | Open the matching panel above the puppy; Menu returns to the main menu |
| Menu (inside a panel) | Go back to the Study / Focus / Chat / More menu |
| Expand / Restore (Chat) | Grow the chat into the monitor workspace or return to its normal size |
| Composer option (Chat) | Choose Chat, Focus plan, Model or Help |
| 📌 (Chat) | Pin a PDF, Word document, TXT or Markdown file to the current chat |
| Focus plan | Read pinned files or a typed subject description, ask for free time if needed, and build a study plan |
| More | Open Focus Planner, Settings, session chat history, Help / About, collapse, or quit |
| Escape | Close the current panel/menu and return to the puppy |
| Right-click | Development menu: switch mascot states or quit |
| Quit Puppy | Closes the window and shuts services down cleanly |

There is deliberately no title bar, so use **More → Quit Puppy** (or the
right-click development menu) to exit.

## Mascot states

| State | Sheet row | Purpose |
| --- | --- | --- |
| `IDLE` | 1 | Default desktop state |
| `HAPPY` | 2 | Positive reactions |
| `THINKING` | 3 | Processing requests |
| `TALKING` | 4 | Delivering responses |
| `STUDYING` | 5 | Study mode |
| `SLEEPING` | 6 | Five idle minutes without a panel or active study timer |

`MascotSprites` owns the animation timing, `MascotAnimator` drives the frames,
and `MascotStateService` is the single place the rest of the application asks
for a state change. `MascotController` also owns the inactivity timer, the
two-minute notification timer and the random click/wake lines in
`MascotMessages`.

## Sprite sheet

`src/main/resources/sprites/new_spritesheet.png` is a **6 × 6 grid**: six
animation frames per row and one row per state, in the order shown above. The
current sheet uses 128 × 128 px cells, but the animator derives the frame size
from the image, so any sheet that stays on the 6 × 6 grid works without code
changes. Frames are played by moving the image view's viewport, which keeps
state switches cheap.

The animator cuts exact cells, so each frame must be drawn on the same anchor:
centred horizontally and with the paws on a common ground line, with effects
kept inside the cell. Breaking that rule makes animations shake and lets parts
of one row appear in the next.

Use `docs/spritesheet-guide.png` (768 × 768, 128 × 128 px cells) as a trace layer
and follow [docs/SPRITESHEET_GUIDE.md](docs/SPRITESHEET_GUIDE.md) for the anchor
rules, row order, an AI prompt and a troubleshooting table.

`src/main/resources/icons/puppy.png` is the window/taskbar icon, cropped from
the idle frame of the sheet.

## Architecture

```
JavaFX presentation   controllers, FXML, CSS, sprite rendering
        │  Java interfaces / method calls
Application services  ChatService, FocusService, MascotStateService (Study, research later)
        │
Infrastructure        Ollama HTTP client, document extraction, configuration,
                      thread pool, repositories later
```

| Component | Current implementation |
| --- | --- |
| `PuppyApplication` | JavaFX entry point, builds the transparent always-on-top stage |
| `ServiceRegistry` | Creates and shuts down application services |
| `AppConfig` | App name, version, always-on-top flag, data directory, Ollama URL and model |
| `MascotController` | Expandable shell, mascot interactions, sleep/notification timers, panel navigation and drag-safe movement |
| `MascotAnimator` | Frame timing and viewport switching |
| `MascotSprites` | Sheet layout and per-state animation metadata |
| `MascotMessages` | Random click, wake and notification lines |
| `MarkdownRenderer` | Safe Markdown-to-JavaFX rendering for chat bubbles |
| `MascotStateService` | Thread-safe current state with listener notifications |
| `ChatService` | Builds the bounded prompt and streams replies from the local model |
| `FocusService` | Owns in-memory classes, tasks, preferences, the current plan and manual-session validation |
| `FocusPlanner` | Deterministic scheduling: classes, free time, deadlines, subject fairness, breaks and block sizes |
| `FocusPlanExtractor` | Reads timetable/task files; the Ollama implementation asks the local model for strict JSON |
| `OllamaClient` | Ollama `/api/chat` streaming, JSON mode, availability checks and friendly errors |
| `ChatPromptBuilder` | System prompt, document context and recent-history limits |
| `DocumentTextExtractor` | PDF (PDFBox), DOCX/DOC (Apache POI), TXT and Markdown extraction |
| `StudyTimer` | Timestamp-based Pomodoro countdown (25:00 focus / 5:00 break, test mode) |
| `MockChatResponder` | Legacy stand-in used by its unit test; no longer connected to the UI |
| `ChatController` / `StudyController` | Streaming chat with attachments and the Pomodoro panel |
| `FocusController` | Import, classes, tasks, manual sessions, plan list, countdown and completion |
| `SettingsController` | In-session desktop settings and local-model information |
| `ThreadManager` | Daemon worker pool for chat, attachments and future background work |

Rules that keep the project clean:

- Controllers never touch databases, HTTP or AI providers; they call services.
- `ServiceRegistry` is the only place services are constructed.
- Background work will never run on the JavaFX Application Thread.
- Runtime data will live in `%LOCALAPPDATA%\PuppyAI`, not inside the project.

## Project layout

```
pom.xml
src/main/java/com/puppy/
├── PuppyApplication.java
├── ai/           OllamaClient, OllamaException
├── config/       AppConfig, ServiceRegistry
├── controller/   MascotController, ChatController, StudyController,
│                 FocusController, SettingsController
├── document/     DocumentTextExtractor, DocumentExtractionException
├── enums/        MascotState, FocusType, Priority, AIMode, MessageRole
├── model/        ChatMessage, DocumentAttachment, ClassSlot, StudyTask,
│                 FocusPreferences, FocusSession, FocusPlan, FocusPlannerInput,
│                 FocusExtraction
├── service/      ChatService, ChatPromptBuilder, FocusService, FocusPlanner,
│                 FocusPlanExtractor, OllamaFocusPlanExtractor, FocusJsonParser,
│                 FocusFormats, MascotStateService, DefaultMascotStateService,
│                 StudyTimer, MockChatResponder
├── ui/           MascotAnimator, MascotSprites, MascotMessages, MarkdownRenderer
└── util/         ThreadManager
src/main/resources/
├── css/          puppy.css
├── fxml/         shell.fxml, chat.fxml, study.fxml, focus.fxml, settings.fxml
├── icons/        puppy.png
└── sprites/      new_spritesheet.png
src/test/java/com/puppy/
├── ai/           OllamaClientTest
├── controller/   FxmlLayoutTest
├── document/     DocumentTextExtractorTest
├── service/      ChatPromptBuilderTest, ChatServiceTest, FocusPlannerTest,
│                 FocusJsonParserTest, FocusServiceTest,
│                 DefaultMascotStateServiceTest, StudyTimerTest,
│                 MockChatResponderTest
└── ui/           MascotSpritesTest, MascotMessagesTest
```

Planned packages from the implementation plan (`repository/`, `database/`,
`web/`) are added in their milestones.

## Focus mode

Focus has two entry points: the **Focus plan** composer option in Chat with
pinned files or a typed subject description, and the Focus Planner panel from
the main menu or More. Both share one in-memory `FocusService`, so a plan built
in chat is immediately visible in the panel.

### How a plan is built

1. Puppy extracts text from up to five pinned PDF, DOCX, DOC, TXT or Markdown
   files (20 MB each), plus any subject description typed in the chat box.
2. The local Ollama model reads that text and returns strict JSON classes,
   tasks and subjects. It only extracts facts; it does not choose study times.
3. If the typed description has no free-time window, Puppy asks for one before
   planning. Ranges such as `18:00-21:30`, `7h-9h`, `7h tối - 9h tối` or
   `7pm-9pm` are accepted; the answer becomes the Focus free-time setting.
   Timetable class times are never mistaken for free time.
4. `FocusPlanner` deterministically places blocks using the rules below.
5. The plan is summarized in chat and Focus Planner opens automatically when
   sessions were created.

### Session types

Puppy chooses the type from your description; there is no Quick/Deep selector
in Chat.

| Type | Default block | Use for |
| --- | --- | --- |
| Quick focus | 25 min | Light lecture review, reading, notes and flashcards |
| Deep focus | 50 min | Important or difficult subjects, low confidence, programming, assignments and research |

A task longer than its default block is split into equal blocks — a
120-minute deep task becomes three 40-minute sessions, not 50 + 50 + 20. Every
session carries its subject, date, start/end time, type and completion state.
Priority is kept internally so Puppy can order the most important work first;
it is not shown or chosen in the UI.

### Scheduling constraints

- Never overlaps a weekly school class, so self-study never lands inside class.
- Stays inside the free-time window (default `08:00 - 22:00`, adjustable).
- Finishes work on or before its deadline; work whose deadline has passed is
  reported instead of being scheduled late.
- Leaves a 10-minute gap between sessions and starts today at least 15 minutes
  from the current time.
- Gives each subject at most two sessions per day and round-robins subjects, so
  one subject cannot consume the whole plan.
- Plans at most 21 days ahead.

### Import and manual editing

- **Focus plan** schedules every task found in the pinned files or typed
  description. Describe a subject as important, difficult, needing more time, or
  not fully mastered and Puppy schedules it as Deep focus; light review, reading
  and notes stay Quick.
- If the description has no start and end time, Puppy asks **"When are you
  free?"** first and continues with the same request after the answer. Saying
  `cancel` drops it.
- If a timetable or subject file has no task list, Puppy adds one
  "Review class notes" quick task per subject, due in 7 days.
- Imported classes and tasks merge with what is already loaded; exact duplicates
  are ignored.
- Focus Planner can add classes, tasks and one-off sessions by hand. A manual
  session is rejected when it is in the past, outside free time, or overlaps a
  class or another session.
- Any session can be edited from its **Edit** button: subject, action, focus
  type, date, start time and duration. Saving re-checks the free-time window,
  class times and every other session that day, ignores the session's own old
  time, and preserves its completion state. **Cancel edit** leaves it untouched.
- Sessions support Start / Pause / Resume, a live countdown, Finish / Done and
  Remove. A running Focus timer keeps the mascot awake.

Focus data is session-only. **Generate plan** rebuilds the scheduled session
list from the current classes and tasks; hand-made sessions are not carried into
a new generation. Persistence is planned with the SQLite milestone.

## Local chat and document attachments

The app follows the mascot → menu → Study / Focus / Chat / More flow. The
expandable shell caches the Chat, Study, Focus and Settings panels; closing or
minimizing a panel returns to the mascot.

- **Chat** opens in a roomy, monitor-aware panel with an Expand / Restore
  toggle. Replies stream from the local Ollama model on the shared worker pool;
  tokens are batched onto an 80 ms UI ticker so a fast model cannot flood the
  JavaFX Application Thread. Blank input is rejected unless a document is
  attached. Assistant replies are rendered as Markdown (headings, emphasis,
  lists, quotes, code, links and tables), and completed messages keep their
  formatted layout in session history. Every finished model reply shows a small
  **Completed in:** footer: seconds under a minute, minutes and seconds under an
  hour, then hours and minutes for long generations.
- **Commands and focus** use the option beside the message box instead of typing
  slash commands. **Focus plan** reads pinned files or the typed description,
  infers Quick or Deep focus from it, and asks for a free-time window when the
  description has none. **Model** shows the exact model name and Ollama server,
  and **Help** lists the options. Model and Help answer locally.
- **Sessions** support a highlighted active conversation, a scrollable history,
  smooth switching and automatic archiving of the conversation you leave. The
  current session is copied into history only when a message completes, not on
  every streamed token.
- **Attachments** use the 📌 pin button and read PDF, DOCX, legacy DOC, TXT and
  Markdown files locally. Text is extracted with PDFBox/Apache POI, capped at
  20 MB and 80,000 characters per file, and only the extracted text is kept in
  memory.
- Up to five documents can be attached to a conversation. Their context stays
  with that conversation until the app exits; remove a chip or start a new chat
  to drop it. The prompt builder caps document context and recent history so a
  large file cannot overflow the model's context window.
- **Errors** stay friendly: Ollama not running, model missing (with the
  `ollama pull` command), timeouts and damaged files are shown in the chat
  without stack traces.
- **Study** keeps the 25-minute focus / 5-minute break timer with
  start/pause/reset and focus/break completion feedback.
- **Focus** plans around classes, deadlines and free time with Quick (25 min) and
  Deep (50 min) sessions, lets you edit or create sessions, then runs a countdown
  for the selected session.
- **Settings** exposes the in-session always-on-top option and shows the
  configured Ollama model and server. Cloud AI modes are not configured yet.
- **More** offers Focus Planner, Settings, session chat history, Help / About,
  collapse-to-mascot and Quit.

Chat history, attachments and focus data are session-only. Persistent history and provider
credentials are not implemented. A true hide-to-tray option is deferred;
“Hide/show” safely collapses the expanded shell to the mascot.

`StudyTimer` remains pure Java; `StudyController` owns the JavaFX ticker and
calls `completePhase()` when a phase finishes. `ChatController` calls
`ChatService` from the shared worker pool and never talks to Ollama or the
document libraries directly. `FocusController` calls `FocusService`; only
`OllamaFocusPlanExtractor` asks the model for JSON, and only `FocusPlanner`
decides when a session runs.

### Attachment limits and supported formats

| Format | Reader | Notes |
| --- | --- | --- |
| PDF | Apache PDFBox | Adds `--- Page N ---` markers; scanned/image-only PDFs are rejected |
| DOCX | Apache POI | Paragraphs and tables (tables use ` \| ` between cells) |
| DOC | Apache POI Scratchpad | Legacy binary Word documents |
| TXT / MD | Java NIO | UTF-8 text |

Passwords, encrypted files and files larger than 20 MB are rejected with a
message in the chat panel. A scanned PDF has no selectable text, so Puppy cannot
read it yet.

## Milestone 1 acceptance

| Check | Covered by |
| --- | --- |
| Application launches | `mvn javafx:run` |
| Transparent window (desktop visible behind the puppy) | `StageStyle.TRANSPARENT` + transparent scene fill |
| Always on top | `stage.setAlwaysOnTop` (configurable) |
| Drag the puppy | `MascotController` mouse handlers |
| All six animations play | Right-click menu, `MascotAnimator` |
| Clean exit | `Application.stop()` → `ServiceRegistry.close()` |

Known limitations: JavaFX receives mouse input across the full window rectangle,
including transparent pixels. Chat history, attachments and focus plans are
session-only, scanned PDFs are not supported, and cloud AI configuration,
persistent settings, a system tray and true hide/show behavior are future work.
Focus extraction needs the local Ollama model, caps combined document context at
24,000 characters, and the planner works within a 21-day horizon. Regenerating a
plan replaces the scheduled session list, including hand-made sessions.
