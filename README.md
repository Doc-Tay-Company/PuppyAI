# Puppy AI

A desktop AI companion: a pixel-art puppy that lives on your desktop, chats with
you, researches the web and runs study sessions. JavaFX frontend with pure Java
services in a single desktop process — no Python, no separate server.

**Implementation roadmap and status:** [Implementation Status](docs/IMPLEMENTATION_STATUS.md)
is the single source of truth for scope, decisions, dependencies and acceptance
criteria. SQLite persistence is implemented. Supplemental chat instructions in
Settings and interactive **Multiple Quiz** inside Chat remain planned; only the
quiz-definition storage foundation exists so far.

**Status: local and cloud AI chat plus Focus planning are wired up.** The app
launches as a frameless, transparent, always-on-top puppy. The mascot stays in
its desktop window while the menu and feature panels open in separate windows.
Click the puppy for a random message, double-click to open Study / Focus / Chat /
More. After five idle minutes the puppy falls asleep; click it to wake it up.
Chat streams Markdown replies from the selected model: **OpenRouter** in the
cloud when a key is configured, or the local **Ollama** model as a fallback.
Puppy offers **Focus plan / Help / Chat** next to the message box; the chosen
option stays active until another one is selected, and `/model` shows the active
model. Chat can read pinned PDF, Word and text files into the conversation.
Settings switches models live and stores the OpenRouter key only on this
computer. Focus planning reads a
pinned timetable or subject file, or a typed subject description, infers Quick
or Deep focus from how you describe the subject, asks for your free-time window
when it is missing, and builds a constraint-aware study plan while the Focus
Planner panel supports hand-made classes, tasks and sessions. Chat, focus data
and extracted attachments persist locally; cloud models need an internet connection and an
OpenRouter key.

## Requirements

| Tool | Version |
| --- | --- |
| JDK | 25 |
| Maven | 3.9+ |
| Ollama | 0.35+ for local chat (optional) |
| OpenRouter API key | Optional; needed for cloud chat |

Because Maven may pick a different JDK than your `PATH`, point `JAVA_HOME` at
your JDK 25 installation before building:

```bash
export JAVA_HOME="C:/path/to/jdk-25"   # Git Bash
# or: set JAVA_HOME=C:\path\to\jdk-25  # cmd.exe
```

## AI models and API keys

Puppy offers the full OpenRouter text-model catalog plus one local fallback.
The compact picker keeps three Gemini suggestions and recent choices; **Browse
all models…** opens the searchable full list with context size, price and
free/JSON tags. Only text models with at least a 16k context are listed.

| Suggested choice | Location | Needs |
| --- | --- | --- |
| Gemini 2.5 Flash | Cloud (OpenRouter) | OpenRouter API key |
| Gemini 2.5 Flash-Lite | Cloud (OpenRouter) | OpenRouter API key |
| Gemini 2.5 Pro | Cloud (OpenRouter) | OpenRouter API key |
| Qwen3.5 9B | Local | Ollama with the model installed |

Open **More > Settings** to switch models. Cloud models are listed even before a
key exists, so you can select one first. Selecting one reveals an **OpenRouter
API key** section directly below the model picker; the local model keeps its name
and never asks for a key. With no saved choice, a configured key defaults to
**Gemini 2.5 Flash**. Choosing the local model keeps chat on this machine. The
choice, the recent-model list and a 24-hour cached copy of the OpenRouter catalog
are saved in `%LOCALAPPDATA%\PuppyAI\puppy.db` (or the platform equivalent).
**Refresh models** re-fetches the public `/models` endpoint; a network failure
only shows a warning and never removes the saved list. The old `ai.properties`
model choice is imported once; that legacy file is left untouched.

To add a key:

1. Open **More > Settings**.
2. Choose an OpenRouter model (for example **OpenRouter · Gemini 2.5 Flash**).
3. Paste the key into the masked **OpenRouter API key** field that appears below.
4. Choose **Save key**. The selected model is ready immediately.

The key is written to `%LOCALAPPDATA%\PuppyAI\ai-secrets.properties` and is never
written to the project folder, docs, tests or logs. Remove it from Settings at
any time; the OpenRouter model stays selected so a new key can be entered. The
file is scoped to your user account but is not encrypted at rest, so protect your
Windows account and rotate the key if it is shared.

You can also provide the key through the environment: `OPENROUTER_API_KEY`,
`PUPPY_OPENROUTER_API_KEY` or `-Dpuppy.openrouter.apiKey=...`. An environment key
wins over one saved in Settings and makes the Settings field read-only. `.env`
files are not parsed automatically.

When a cloud model is selected, attached document text is sent to OpenRouter and
the model provider it routes to along with the conversation. The local model
keeps that text on this computer. Cloud requests go to
`https://openrouter.ai/api/v1` unless `PUPPY_OPENROUTER_URL` or
`-Dpuppy.openrouter.url` overrides it.

## Local AI setup (Ollama + Qwen3.5 9B)

Puppy does not bundle a local model. Ollama is only needed when you choose the
local model or when no cloud key is configured. Install [Ollama](https://ollama.com/),
then make sure the local model is available:

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

The local model label in Settings follows the configured name. Changing
`PUPPY_OLLAMA_MODEL` changes the saved local model id, so Puppy falls back to
the default selection on the next launch.

Puppy reads environment variables and JVM system properties; it does not parse
a `.env` file automatically.

If Ollama is not running or the model is missing, the chat panel shows a
friendly message with the command to fix it - it does not crash.

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
| Study / Focus / Chat | Open the matching panel in a separate window without moving the mascot |
| Menu (inside a panel) | Return to the Study / Focus / Chat / More menu |
| Expand / Restore (Chat) | Grow the chat into the monitor workspace or return to its normal size |
| Attach | Add a PDF, Word document, TXT or Markdown file to the current chat |
| Chat options | Choose Chat, Focus plan or Help beside the message box; the choice stays active until you pick another |
| Right-click a chat message | Copy it, or reuse it in the message box |
| Ctrl+V in the message box | Paste copied text |
| `/model` | Keyboard fallback to show the selected model and provider |
| More | Open Focus Planner, Settings, session chat history, Help / About, collapse, or quit |
| Settings | Switch between OpenRouter cloud models and the local model, and manage the OpenRouter key |
| Escape | Close the current panel/menu and return to the puppy |
| Right-click | Development menu: switch mascot states or quit |
| Quit Puppy | Closes the window and shuts services down cleanly |

There is deliberately no title bar, so use **More → Quit Puppy** (or the
right-click development menu) to exit.

## Local database and privacy

Puppy uses embedded SQLite through JDBC; no database server is required.
`puppy.db` lives in `%LOCALAPPDATA%\PuppyAI` (or
`~/.local/share/PuppyAI`). Override the directory with
`-Dpuppy.dataDirectory=C:\path\to\data` or `PUPPY_DATA_DIRECTORY`.
Use a local disk. Only one running Puppy instance can own a data directory.

- Chat messages, reply timings, conversation attachments, Focus data/completion,
  model choice and always-on-top survive restart. The last active chat reopens;
  history is ordered by recent activity. Greeting-only empty chats are not saved.
- New chats receive a provisional title, then one additional AI request asks the
  selected model to paraphrase the first request. It does not include attachments.
  Cloud titles may incur provider charges. Failure retains the provisional title
  and shows a nonblocking warning.
- Attachments store extracted text and filename/page/truncation metadata, never
  original file copies or absolute paths. Moving the original does not break
  restored context. Removing a chip deletes its stored text from that chat,
  while old message filename labels remain.
- Local chat/document data is **not encrypted**. Credentials remain in the
  separate `ai-secrets.properties` file; no `.env` migration is performed.
  External environment/system-property API keys retain precedence.
- Failed chat saves retain content in memory and expose **Retry save**. A user
  request must save before AI generation starts. Interrupted replies are labelled;
  forced termination may lose tokens since the last saved snapshot. Quit drains
  writes and offers retry or explicit quit-without-saving on failure.
- Database startup/migration failures block normal startup rather than silently
  replacing existing data. Upgrades keep one consistent pre-upgrade backup,
  `puppy.pre-upgrade.db`; recovery is manual. Newer schemas require a newer app.
- Deletion uses SQLite `secure_delete` with rollback journaling. This is best
  effort, **not a physical-erasure guarantee** on SSDs, filesystem snapshots or
  backups. Protect the data directory and any copies yourself.
- Focus countdowns are not restored. Quiz storage saves definitions only, never
  learner attempts, answers, progress or scores; quiz generation/UI is not yet
  implemented. New history-management/search/export UI is deferred.

## Mascot states

| State | Frames | Purpose |
| --- | --- | --- |
| `IDLE` | 3 | Default desktop state |
| `HAPPY` | 2 | Positive reactions |
| `THINKING` | 2 | Processing requests |
| `TALKING` | 2 | Delivering responses |
| `STUDYING` | 2 | Study mode |
| `SLEEPING` | 2 | Five idle minutes without a panel or active study timer |

`MascotSprites` owns the animation timing, `MascotAnimator` drives the frames,
and `MascotStateService` is the single place the rest of the application asks
for a state change. `MascotController` also owns the inactivity timer, the
two-minute notification timer and the random click/wake lines in
`MascotMessages`.

## Sprite images

Each animation uses separate transparent PNG frames in
`src/main/resources/sprites/<state>/`, named `0.png`, `1.png`, and so on. The
animator loads them once and switches the displayed image; it does not slice a
spritesheet.

Frame timing is per image: IDLE, HAPPY, STUDYING and TALKING use 2,000 ms;
THINKING uses 500 ms; SLEEPING uses 1,000 ms. Keep character scale and paw
baseline consistent within each state to avoid visible jumps.

The old spritesheet instructions are a legacy reference only; the runtime uses
the numbered frame images. `src/main/resources/icons/puppy.png` is the
window/taskbar icon.

## Architecture

```
JavaFX presentation   controllers, FXML, CSS, sprite rendering
        │  Java interfaces / method calls
Application services  ChatService, ModelService, FocusService, MascotStateService
        │
Infrastructure        SQLite/JDBC repositories, Ollama and OpenRouter HTTP clients,
                      document extraction, local key storage, configuration, thread pool
```

| Component | Current implementation |
| --- | --- |
| `PuppyApplication` | JavaFX entry point, builds the transparent always-on-top stage |
| `ServiceRegistry` | Creates and shuts down application services, including `ModelService` |
| `AppConfig` | App name, version, always-on-top flag, data directory, Ollama URL/model, optional OpenRouter environment key and endpoint |
| `AiSettingsStore` | SQLite-backed model choice with one-time legacy import; separate local OpenRouter secret file |
| `Database` / repositories | Locked per-user SQLite database, serialized JDBC work, transactional storage and schema upgrades |
| `ChatHistoryService` / `AppSettingsService` | Durable chat snapshots/retry and committed desktop preferences |
| `MascotController` | Fixed mascot window, separate menu/panel windows, mascot interactions and timers |
| `MascotAnimator` | Frame timing and per-state image switching |
| `MascotSprites` | Ordered frame resources and per-state animation metadata |
| `MascotMessages` | Random click, wake and notification lines |
| `MarkdownRenderer` | Safe Markdown-to-JavaFX rendering for chat bubbles |
| `MascotStateService` | Thread-safe current state with listener notifications |
| `ChatService` | Builds the bounded prompt and streams replies from the selected model |
| `ModelService` | Cloud-first model selection, runtime switching, key state and provider routing |
| `FocusService` | Owns restored classes, tasks, preferences, current plan and manual-session validation; mutations persist before publication |
| `FocusPlanner` | Deterministic scheduling: classes, free time, deadlines, subject fairness, breaks and block sizes |
| `FocusPlanExtractor` | Reads timetable/task files; `AiFocusPlanExtractor` asks the selected model for strict JSON |
| `ChatProvider` | Provider-neutral contract for streaming chat and JSON mode |
| `OllamaClient` | Ollama `/api/chat` streaming, JSON mode, availability checks and friendly errors |
| `OpenRouterClient` | OpenRouter `/chat/completions` SSE streaming, JSON mode, bearer-key header, redacted errors and cloud-friendly messages |
| `ChatPromptBuilder` | Provider-neutral system prompt, document context and recent-history limits |
| `DocumentTextExtractor` | PDF (PDFBox), DOCX/DOC (Apache POI), TXT and Markdown extraction |
| `StudyTimer` | Timestamp-based Pomodoro countdown (25:00 focus / 5:00 break, test mode) |
| `MockChatResponder` | Legacy stand-in used by its unit test; no longer connected to the UI |
| `ChatController` / `StudyController` | Streaming chat with attachments and the Pomodoro panel |
| `FocusController` | Import, classes, tasks, manual sessions, plan list, countdown and completion |
| `SettingsController` | Desktop settings, live model switching, model-aware OpenRouter key section and cloud/local status |
| `ThreadManager` | Daemon worker pool for chat, attachments and future background work |

Rules that keep the project clean:

- Controllers never touch databases, HTTP or AI providers; they call services.
- `ServiceRegistry` is the only place services and providers are constructed.
- Model choice is persisted locally; API keys never enter the repository.
- Background work will never run on the JavaFX Application Thread.
- Runtime data lives in `%LOCALAPPDATA%\PuppyAI`, not inside the project.

## Project layout

```
pom.xml
src/main/java/com/puppy/
├── PuppyApplication.java
├── ai/           AiException, AiModel, AiModels, ChatProvider, OpenRouterClient,
│                 OllamaClient
├── config/       AiSettingsStore, AppConfig, ServiceRegistry
├── controller/   MascotController, ChatController, StudyController,
│                 FocusController, SettingsController
├── database/     Database, PersistenceException
├── document/     DocumentTextExtractor, DocumentExtractionException
├── enums/        MascotState, FocusType, Priority, AIMode, MessageRole
├── model/        ChatMessage, DocumentAttachment, ClassSlot, StudyTask,
│                 FocusPreferences, FocusSession, FocusPlan, FocusPlannerInput,
│                 FocusExtraction, FocusState, StoredConversation, StoredMessage,
│                 QuizDefinition
├── repository/   SettingsRepository, ChatRepository, FocusRepository, QuizRepository
├── service/      ChatService, ChatPromptBuilder, ModelService, FocusService,
│                 FocusPlanner, FocusPlanExtractor, AiFocusPlanExtractor,
│                 FocusJsonParser, FocusFormats, MascotStateService,
│                 DefaultMascotStateService, StudyTimer, MockChatResponder,
│                 ChatHistoryService, AppSettingsService
├── ui/           MascotAnimator, MascotSprites, MascotMessages, MarkdownRenderer
└── util/         ThreadManager
src/main/resources/
├── css/          puppy.css
├── fxml/         shell.fxml, chat.fxml, study.fxml, focus.fxml, settings.fxml
├── icons/        puppy.png
└── sprites/      state folders with numbered PNG frames
src/test/java/com/puppy/
├── ai/           OpenRouterClientTest, OllamaClientTest
├── config/       AiSettingsStoreTest, DatabaseSettingsTest
├── controller/   FxmlLayoutTest, FocusControllerWorkerTest
├── database/     DatabaseTest
├── document/     DocumentTextExtractorTest
├── service/      ChatPromptBuilderTest, ChatServiceTest, ModelServiceTest,
│                 FocusPlannerTest, FocusJsonParserTest, FocusServiceTest,
│                 DefaultMascotStateServiceTest, StudyTimerTest,
│                 MockChatResponderTest, FocusPersistenceTest
├── repository/   ChatRepositoryTest, QuizRepositoryTest
├── ui/           MascotSpritesTest, MascotMessagesTest
└── util/         DurationFormatsTest
```

Persistence packages (`repository/`, `database/`) implement the storage tracked
in the [implementation status](docs/IMPLEMENTATION_STATUS.md). Web research is
outside the current feature scope.

## Focus mode

Focus has two entry points: the **Focus plan** composer option in Chat with
pinned files or a typed subject description, and the Focus Planner panel from
the main menu or More. Both share one persisted `FocusService`, so a plan built
in chat is immediately visible in the panel.

### How a plan is built

1. Puppy extracts text from up to five pinned PDF, DOCX, DOC, TXT or Markdown
   files (20 MB each), plus any subject description typed in the chat box.
2. The selected model reads that text and returns strict JSON classes, tasks,
   subjects and subjects that need extra time. OpenRouter and Ollama share the
   same prompt; only the transport changes. The model only extracts facts; it
   does not choose study times.
3. If no free-time window is saved yet, Puppy asks for one before planning.
   Ranges such as `18:00-21:30`, `7h-9h`, `7h tối - 9h tối` or `7pm-9pm` are
   accepted; the answer becomes the Focus free-time setting and later plans
   reuse it. Change it any time by saying `change time to 18:00-22:00` in
   **Adjust plan** or with **Change time** in Focus Planner. Timetable class
   times are never mistaken for free time.
4. The plan covers one week by default. Say `trong 3 ngày`, `for 2 weeks`,
   `1 tháng` or give an appointment such as `deadline 20/11` to choose another
   end date; an explicit date wins over a duration.
5. `FocusPlanner` deterministically places blocks using the rules below.
6. The plan is summarized in chat and Focus Planner opens automatically when
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
- Uses a two-pass queue per subject: every subject gets at most one session in
  the first pass, and a second session is allowed only after every subject had a
  first one. The starting subject rotates each day, each subject stays at two
  sessions per day, and while several subjects still need time a second session
  may not push a subject past half of that day's free window.
- Uses a one-week horizon by default. A stated duration (`3 ngày`, `2 tuần`,
  `1 tháng`) or deadline/date (`deadline 20/11`, `trước 2026-12-01`) replaces it;
  durations are inclusive, dates without a year use their next non-past
  occurrence, and the horizon is capped at one year. When no end is stated, the
  latest task deadline extends the week if it is later. The requested end lasts
  for one generated plan only.

### Import and manual editing

- **Focus plan** schedules every subject found in the pinned files or typed
  description. Saying a subject is important, difficult, needs more time, or is
  not fully mastered marks it for extra time: its tasks grow by 50% or 30
  minutes, whichever is larger, while the other subjects keep their normal
  reviews. Light review, reading and notes stay Quick.
- If the description has no start and end time, Puppy reuses the window you
  already saved. A first plan asks **"When are you free?"** once; later plans
  skip the question. Say **change time to 18:00-22:00** in Adjust plan, or use
  **Change time** in Focus Planner, to choose a different window. Saying
  `cancel` drops a pending request.
- Every subject in a subject list gets its own "Review class notes" quick task,
  even when other tasks exist. A timetable subject with no task at all gets the
  same review fallback. Reviews are due at the end of the default week
  (today + 6 days).
- Imported classes merge with what is already loaded. Tasks merge by normalised
  subject and title, so importing the same work again updates its duration or
  deadline instead of adding a duplicate.
- Focus Planner can add classes, tasks and one-off sessions by hand. A manual
  session is rejected when it is in the past, outside free time, or overlaps a
  class or another session.
- Any session can be edited from its **Edit** button: subject, action, focus
  type, date, start time and duration. **Apply to all sessions of subject**
  copies those fields to the subject's other sessions while keeping each date,
  title and completion state. Saving re-checks the free-time window, class times
  and every other session that day, ignores the session's own old time, and
  preserves its completion state. **Cancel edit** leaves it untouched.
- **Undo** below the session list reverts the last hand-made or AI change.
  Generate plan and Import ask before replacing edited sessions; **Clear plan**
  resets everything without asking.
- Sessions support Start / Pause / Resume, a live countdown, Finish / Done and
  Remove. A running Focus timer keeps the mascot awake.

Focus data survives restart. **Generate plan** rebuilds the scheduled session
list from the current classes and tasks; hand-made sessions are not carried into
a new generation. Restart preserves past sessions/completion but never resumes a countdown.

## Chat, attachments and model switching

The app follows the mascot → menu → Study / Focus / Chat / More flow. The mascot
stays in its fixed desktop window while the menu and feature panels use separate
windows. Chat, Study, Focus and Settings views are cached between openings.

- **Chat** opens centered on the primary screen in a roomy window with an
  Expand / Restore toggle. Replies stream from the selected model on the shared
  worker pool - OpenRouter in the cloud when its key is configured, or Ollama
  locally. Tokens are batched onto an 80 ms UI ticker so a fast model cannot
  flood the JavaFX Application Thread. Blank input is rejected unless a document
  is attached. Assistant replies are rendered as Markdown (headings, emphasis,
  lists, quotes, code, links and tables), and completed messages keep their
  formatted layout in session history. Every finished model reply shows a small
  **Completed in:** footer: seconds under a minute, minutes and seconds under an
  hour, then hours and minutes for long generations.
- **Copy and reuse** previous requests: every message has a right-click
  **Copy message** / **Reuse in message box** menu. Your own messages also show
  small **Copy** and **Reuse** buttons below the bubble. Reuse puts the exact
  request back in the composer without sending it, and normal **Ctrl+V** pastes
  clipboard text into the message box.
- **Commands and focus** use the option beside the message box instead of typing
  slash commands. **Focus plan** reads pinned files or the typed description,
  infers Quick or Deep focus from it, and asks for a free-time window only when
  none is saved. **Adjust plan** turns a request such as "move Math to tomorrow
  at 19:00", "make the Physics sessions 75 minutes", "change Wednesday to
  Chemistry", "delete the Friday review", or "my free time is 18:00-22:00" into
  structured plan edits. Every edit is checked against classes, free time and
  other sessions, applied all-or-nothing, and can be undone in Focus Planner.
  The selected option stays active after every reply and choosing another one
  replaces it. **Help** lists the options and answers locally; `/model` still
  shows the exact model, provider and whether it is cloud or local.
- **Sessions** support a highlighted active conversation, a scrollable history,
  smooth switching and automatic archiving of the conversation you leave. The
  current session is copied into history only when a message completes, not on
  every streamed token.
- **Attachments** use the 📌 pin button and read PDF, DOCX, legacy DOC, TXT and
  Markdown files locally. Text is extracted with PDFBox/Apache POI, capped at
  20 MB and 80,000 characters per file, and only the extracted text is kept in
  local storage. When a cloud model is selected, that extracted text is sent to
  OpenRouter and its model provider with the prompt; the local model keeps it on
  this computer.
- Up to five documents can be attached to a conversation. Their context stays
  with that conversation across restarts; removing a chip drops its context from
  that chat. A new chat starts with no attachments. The prompt builder caps document context and recent history so a
  large file cannot overflow the model's context window.
- **Errors** stay friendly: Ollama not running, local model missing (with the
  `ollama pull` command), a missing or rejected OpenRouter key, credits,
  quota/rate limits, network failures, timeouts and damaged files are shown in
  the chat without stack traces. The OpenRouter key is never echoed in an error.
- **Study** keeps the 25-minute focus / 5-minute break timer with
  start/pause/reset and focus/break completion feedback.
- **Focus** plans around classes, deadlines and free time with Quick (25 min) and
  Deep (50 min) sessions, lets you edit or create sessions, then runs a countdown
  for the selected session.
- **Settings** switches between OpenRouter cloud models and the local model,
  shows a cloud/local indicator, and reveals a masked OpenRouter key section
  directly below the picker only while a cloud model is selected. Cloud models
  stay listed before a key exists; with no saved choice, a key defaults to
  Gemini 2.5 Flash. The local model needs no key.
- **More** offers Focus Planner, Settings, session chat history, Help / About,
  collapse-to-mascot and Quit.

Chat history, extracted attachments, Focus data, model choice and desktop settings
persist in SQLite; the OpenRouter key remains a separate local file. A true hide-to-tray option is deferred;
“Hide/show” safely collapses the expanded shell to the mascot.

`StudyTimer` remains pure Java; `StudyController` owns the JavaFX ticker and
calls `completePhase()` when a phase finishes. `ChatController` calls
`ChatService` from the shared worker pool and never talks to Ollama, OpenRouter
or the document libraries directly. `FocusController` calls `FocusService`; only
`AiFocusPlanExtractor` asks the selected model for JSON, and only `FocusPlanner`
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
including transparent pixels. Chat history, extracted attachments, Focus plans,
model choice and always-on-top persist locally. A system tray, custom instructions
editor and true hide/show behavior are future work.
Focus extraction uses the selected model, so cloud extraction needs a working
key and internet connection while local extraction needs Ollama running. Combined
document context is capped at 24,000 characters, and the planner defaults to a
one-week horizon (overridable per request, capped at one year). Regenerating a
plan replaces the scheduled session list,
including hand-made sessions. The local key file is user-scoped but not
encrypted at rest.
