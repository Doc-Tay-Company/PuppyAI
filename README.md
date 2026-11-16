# Puppy AI

A desktop AI companion: a pixel-art puppy that lives on your desktop, chats with
you, researches the web and runs study sessions. JavaFX frontend with pure Java
services in a single desktop process — no Python, no separate server.

**Status: local AI chat is wired up.** The app launches as a frameless,
transparent, always-on-top puppy. Click the puppy for a random message,
double-click to open Study / Chat / More, and the shell expands around the
mascot while keeping its screen anchor. After five idle minutes the puppy falls
asleep; click it to wake it up. Chat streams Markdown replies from the local
model through **Ollama**, supports `/model` and `/help`, and can read attached
PDF, Word and text files into the conversation. Chat history and attachments are
session-only; cloud AI and persistence are future work.

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
| Double-click the puppy | Open or close the Study / Chat / More menu |
| Idle for five minutes | Puppy falls asleep; click it to wake it up |
| Every two minutes | Puppy may show a quiet notification while idle |
| Study / Chat | Open the matching panel above the puppy; Menu returns to the main menu |
| Menu (inside a panel) | Go back to the Study / Chat / More menu |
| Expand / Restore (Chat) | Grow the chat into the monitor workspace or return to its normal size |
| Attach | Add a PDF, Word document, TXT or Markdown file to the current chat |
| `/model` | Show the exact local model and Ollama server in the chat |
| More | Open Settings, session chat history, Help / About, collapse, or quit |
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
Application services  ChatService, MascotStateService (Study, research later)
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
| `OllamaClient` | Ollama `/api/chat` streaming, availability checks and friendly errors |
| `ChatPromptBuilder` | System prompt, document context and recent-history limits |
| `DocumentTextExtractor` | PDF (PDFBox), DOCX/DOC (Apache POI), TXT and Markdown extraction |
| `StudyTimer` | Timestamp-based Pomodoro countdown (25:00 focus / 5:00 break, test mode) |
| `MockChatResponder` | Legacy stand-in used by its unit test; no longer connected to the UI |
| `ChatController` / `StudyController` | Streaming chat with attachments and the Pomodoro panel |
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
│                 SettingsController
├── document/     DocumentTextExtractor, DocumentExtractionException
├── enums/        MascotState, AIMode, MessageRole
├── model/        ChatMessage, DocumentAttachment
├── service/      ChatService, ChatPromptBuilder, MascotStateService,
│                 DefaultMascotStateService, StudyTimer, MockChatResponder
├── ui/           MascotAnimator, MascotSprites, MascotMessages, MarkdownRenderer
└── util/         ThreadManager
src/main/resources/
├── css/          puppy.css
├── fxml/         shell.fxml, chat.fxml, study.fxml, settings.fxml
├── icons/        puppy.png
└── sprites/      new_spritesheet.png
src/test/java/com/puppy/
├── ai/           OllamaClientTest
├── controller/   FxmlLayoutTest
├── document/     DocumentTextExtractorTest
├── service/      ChatPromptBuilderTest, ChatServiceTest,
│                 DefaultMascotStateServiceTest, StudyTimerTest,
│                 MockChatResponderTest
└── ui/           MascotSpritesTest, MascotMessagesTest
```

Planned packages from the implementation plan (`repository/`, `database/`,
`web/`) are added in their milestones.

## Local chat and document attachments

The app follows the mascot → menu → Chat / Study / More flow. The expandable
shell caches the Chat, Study and Settings panels; closing or minimizing a panel
returns to the mascot.

- **Chat** opens in a roomy, monitor-aware panel with an Expand / Restore
  toggle. Replies stream from the local Ollama model on the shared worker pool;
  tokens are batched onto an 80 ms UI ticker so a fast model cannot flood the
  JavaFX Application Thread. Blank input is rejected unless a document is
  attached. Assistant replies are rendered as Markdown (headings, emphasis,
  lists, quotes, code, links and tables), and completed messages keep their
  formatted layout in session history.
- **Commands** run locally without contacting the model: `/model` shows the
  exact model name and Ollama server, and `/help` lists the available commands.
  The new-chat greeting intentionally does not name the model.
- **Sessions** support a highlighted active conversation, a scrollable history,
  smooth switching and automatic archiving of the conversation you leave. The
  current session is copied into history only when a message completes, not on
  every streamed token.
- **Attachments** read PDF, DOCX, legacy DOC, TXT and Markdown files locally.
  Text is extracted with PDFBox/Apache POI, capped at 20 MB and 80,000
  characters per file, and only the extracted text is kept in memory.
- Up to five documents can be attached to a conversation. Their context stays
  with that conversation until the app exits; remove a chip or start a new chat
  to drop it. The prompt builder caps document context and recent history so a
  large file cannot overflow the model's context window.
- **Errors** stay friendly: Ollama not running, model missing (with the
  `ollama pull` command), timeouts and damaged files are shown in the chat
  without stack traces.
- **Study** keeps the 25-minute focus / 5-minute break timer with
  start/pause/reset and focus/break completion feedback.
- **Settings** exposes the in-session always-on-top option and shows the
  configured Ollama model and server. Cloud AI modes are not configured yet.
- **More** offers Settings, session chat history, Help / About,
  collapse-to-mascot and Quit.

Chat history and attachments are session-only. Persistent history and provider
credentials are not implemented. A true hide-to-tray option is deferred;
“Hide/show” safely collapses the expanded shell to the mascot.

`StudyTimer` remains pure Java; `StudyController` owns the JavaFX ticker and
calls `completePhase()` when a phase finishes. `ChatController` calls
`ChatService` from the shared worker pool and never talks to Ollama or the
document libraries directly.

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
including transparent pixels. Chat history and attachments are session-only,
scanned PDFs are not supported, and cloud AI configuration, persistent settings,
a system tray and true hide/show behavior are future work.
