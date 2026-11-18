# Puppy AI — Implementation Status

Last updated: 2026-11-18

## Document ownership and current scope

This document is the **single source of truth for implementation scope,
decisions, work order, acceptance criteria and status**. README describes the
released/current app and links here; do not create a separate milestone plan.
Update the work ledger below as implementation and verification actually land.
Older completed sections are a historical record, not evidence that planned
features already exist.

Current goal: local SQLite persistence, editable supplemental chat instructions
in Settings, and interactive multiple-choice quizzes entirely inside Chat.
Database delivery is implemented and verified. Supplemental instructions and the
interactive quiz feature remain pending; quiz definition storage is ready.

### Database delivery plan (approved 2026-11-18)

This delivery implements DB-1 through DB-4 and the definition-storage portion of
QUIZ-2, without quiz generation/UI or the instructions editor.

- Store `puppy.db` in the per-user data directory, overridable by
  `puppy.dataDirectory` / `PUPPY_DATA_DIRECTORY`. One process owns each directory
  through an OS file lock. Use serialized JDBC work, foreign keys, bounded busy
  waits, secure_delete and rollback journaling (no persistent WAL copies).
- Schema v1: settings/migration markers; conversations and ordered messages;
  per-conversation extracted attachments without absolute paths; Focus state
  with stable internal class/task IDs and existing session IDs; quiz definitions
  with ordered validated questions/options and typed chat references. No quiz
  attempts, scores or timer state.
- Transactional schema upgrades take one consistent pre-upgrade backup and reject
  newer versions. Startup failures are visible and block normal operation.
- Import legacy non-secret model selection once, preserving the old properties
  file. **Keep ai-secrets.properties; do not introduce .env.** Explicit desktop
  launch overrides win over saved settings. Non-secret settings use SQLite.
- Save first-user-content chats immediately, restore the last active chat and
  order history by activity. Generate one short paraphrased title through the
  selected model, without attachments; provisional local title and a visible
  nonblocking warning on title failure.
- Save the user before model generation, preserve cancelled/error partial replies
  with status, recover unfinished replies as interrupted, and exclude error
  metadata from future prompts. Keep failed chat snapshots in memory with Retry.
- Focus/settings publish only committed changes; UI mutations move to workers.
  No plan-version archive, implicit session deletion, or changed regeneration.
- Removing an attachment removes its stored text, not historical filename labels.
  Best-effort SQLite deletion is **not** a physical-erasure guarantee on SSDs,
  snapshots or pre-upgrade backups. Storage remains local and unencrypted.
- Quit drains accepted saves; failures offer Retry or explicit Quit without
  saving. Forced termination can lose streamed tokens since the last snapshot.
- Validate temporary-database restart, locking, migration/rollback, credentials
  separation, chat/attachment/status ordering, Focus rollback/restore and quiz
  definition-only round trips; run targeted tests then the integrated Maven suite.

Execution order: foundation/schema -> repositories/settings migration -> service
integration -> worker-safe UI/save recovery -> tests -> update this document with
actual results and a concrete remaining-feature plan. No separate plan file.

### Database delivery outcome (2026-11-18)

- DB-1 through DB-4 are implemented, including worker-safe Focus/settings
  mutations, history restoration, failed-save retry and startup/quit handling.
  API keys remain in `ai-secrets.properties`; no `.env` was added.
- Actual schema v1: `settings`, `conversations`, `messages`, `attachments`,
  `focus_state`, `quizzes`, `quiz_questions`, plus conversation/message indexes.
  Messages have stable IDs, ordering, lifecycle status and TEXT/QUIZ entry type.
  Foreign keys cascade from conversation to messages/attachments and quiz content.
- Focus is stored as one validated JSON snapshot in `focus_state`, with stable
  internal class/task IDs, existing session IDs, preferences and plan metadata.
  This intentionally keeps the current-plan aggregate atomic instead of adding
  unused relational joins or plan-version history. Domain records and scheduling
  behavior stay unchanged. JSON dates/times use Jackson's Java-time support.
- Quiz questions store four ordered options as validated JSON, a correct index,
  explanation and difficulty. Repository validation enforces 1-20 questions and
  distinct options. This is storage infrastructure only: no interactive quiz card,
  attempt logic, generation or progress persistence ships in this delivery.
- First user content (including an attachment) creates durable history; the last
  active conversation is restored. Titles are generated once from the first
  textual request, with a provisional title and visible warning on failure.
- Sidebar ordering uses new-message activity only. Switching conversations,
  saving cancellation/attachment snapshots, and closing/reopening Chat preserve
  existing activity timestamps. Adding a message promotes that conversation.
- Attachments restore from extracted text with filename-only paths. Original
  documents are not needed. Removing a chip removes stored text for that chat;
  historical filename labels are retained. Data and backups are unencrypted.
- SQLite uses a per-directory OS lock, serialized connection access, foreign
  keys, five-second busy timeout, FULL synchronization, rollback journaling and
  `secure_delete`. Backups/snapshots/SSD remnants are outside its erasure control.
- Verification: **`mvn -q test` - 127 tests, 0 failures, 0 errors, 0 skipped**.
  Includes real temporary-database reopen, actual UI-created history round-trip,
  second-instance lock rejection, interrupted-reply recovery, transactional
  rollback, migration backup/failure/newer-version rejection, atomic legacy
  model import, credential separation, Focus mutation rollback/identity/restore,
  JavaFX worker handoff, and quiz definition-only persistence/cascades.
- AI title generation is tested against a local HTTP stub, including malformed
  output; JavaFX layouts and restored chat rendering are exercised automatically.
  **Live Ollama/OpenRouter interaction and manual desktop restart/quit dialogs
  have not been smoke-tested.** Native-access/unnamed-JavaFX warnings and the
  pre-existing PDFBox/POI logging-provider warning remain non-failing.

### Concrete next implementation plan

1. **PROMPT-1 - supplemental instructions:** add a bounded non-secret setting
   and cached service accessor; add a multiline Settings editor with Save/Reset;
   compose delimited preferences below the fixed persona without changing Focus
   or Quiz contracts. Verify persistence, failed-save behavior, exact outbound
   prompt shape and next-request-only changes; test small-screen Settings fit.
2. **QUIZ-1 - generation and learning logic:** use existing `QuizDefinition` and
   `ModelService.jsonChat`; extract count/difficulty requests (5/Medium defaults,
   1-20 questions, mixed per-question difficulty), validate the complete result,
   and implement deterministic in-memory checking/navigation/scoring/retry.
   Test malformed output, wrong counts/options, mixed difficulty and no AI calls
   for grading or retry. No attempt/score fields may enter persisted history.
3. **QUIZ-2 remaining integration:** atomically attach a definition to an ordered
   QUIZ chat entry; connect repository reads to typed history restoration.
   Preserve entry types when UI snapshots are rebuilt. Reopening after process
   restart must create an entirely unanswered attempt from the saved definition.
4. **QUIZ-3 - Chat-only interaction:** add Multiple Quiz to the composer and
   original Puppy-styled cards, one question at a time, Previous/Next, Check
   answer, locked feedback, final score/review and Retry. Keep normal Chat usable;
   isolate attempts/cancellation callbacks by chat and quiz ID.
5. **VERIFY-1 - release gate:** run focused tests then the full suite, manually
   verify local/cloud generation, failure/quit/restart flows and screen fit,
   update README for newly shipped behavior and close ledger items only with
   recorded evidence. Retest that quiz selections/scores never reach SQLite.

### Pre-delivery baseline (historical code inspection, 2026-11-18)

- Java 25 / JavaFX 25.0.5, one desktop process, Maven and JUnit. No SQLite/JDBC
  dependency, database initialization, schema migrations or repositories exist.
  `ServiceRegistry.start()` currently contains only a future-database placeholder.
- `ChatController` owns private `Message` and `Conversation` records, active
  messages, archived conversations and extracted attachments. These have no
  durable conversation/message identities. `ChatMessage` is the provider request
  DTO, not a persisted history record. Reply timing lives in the UI's message
  record. A new active conversation is not archived until the user leaves it.
- `FocusService` owns in-memory classes, tasks, preferences and the current plan;
  sessions already have string IDs. Mutations are synchronized, but controllers
  call many of them directly on the JavaFX thread. Adding blocking database
  writes to these methods without changing their call sites would freeze the UI.
- `AiSettingsStore` persists model selection in `ai.properties` and the API key
  separately in `ai-secrets.properties`. Environment/system-property keys take
  precedence. Always-on-top is currently an in-session setting.
- `ChatPromptBuilder` already supplies a fixed Puppy persona, document rules
  and bounded history in one leading system message. `AiFocusPlanExtractor` has
  its own fixed JSON contract. Both providers support `ModelService.jsonChat`.
- Chat offers Chat / Focus plan / Model / Help. No quiz domain, service,
  generation/parser, interactive card or persistence exists. Study is currently
  a Pomodoro timer, not a quiz engine.
- Existing coverage includes provider clients, settings, bounded prompts,
  document extraction, Focus services/planner, timers and JavaFX layouts.
  This describes the code before the database delivery; use the outcome above
  for current verification rather than historical test counts below.

### Confirmed product decisions

| Area | Decision |
| --- | --- |
| Database | Embedded SQLite, accessed through JDBC; local, single-user, no separate backend. Save past chats, study planner data and reusable quiz content. |
| Chat persistence | Save conversations, ordered messages, reply timing, extracted attachment text and metadata. Never copy original files. Restored attachments must work even if the originals move or disappear. |
| Focus persistence | Save classes, tasks, preferences, the current plan, warnings/generated date and session completion. Keep existing scheduling and regeneration semantics. |
| Settings | Migrate non-secret settings to SQLite, including model choice; persist always-on-top and supplemental chat instructions. Keep the API key in its existing separate secret file and preserve environment-key precedence. |
| Instructions | Add user instructions on top of, not instead of, the fixed Puppy persona. They must not take precedence over application rules. Keep document rules and structured Focus/Quiz contracts application-controlled. |
| Quiz entry | Add **Multiple Quiz** alongside Focus plan in the Chat composer. All quiz setup, questions, feedback and review live in Chat; no separate Quiz panel or automatic Focus integration. |
| Quiz sources | Typed topic/description and/or the current chat's attached documents, using the selected local or cloud model. Existing attachment limits and cloud disclosure still apply. |
| Quiz setup | Default **5 questions / Medium** without an extra setup round. Users specify **1-20** questions and difficulty in the chat request, including different difficulties for individual questions. |
| Quiz questions | Single-answer multiple choice, exactly four options and one correct answer per question. Include an explanation and per-question difficulty. |
| Interaction | Show one question at a time, with Previous/Next. Choose an option then Check answer; reveal the answer/explanation and lock that question. Unanswered questions can be skipped; final submission requires all questions answered. |
| Scoring/retry | Score by the number of correct answers; final score and review. Retry reuses saved questions without another model call and starts a fresh attempt. |
| Quiz persistence boundary | Save only quiz definitions: questions, options, correct answers, explanations, difficulty and their link to the chat. **Never persist attempts, user selections, scores, navigation position or progress**, including completed attempts. Every quiz restarts unanswered after app restart. Scores and review exist only in memory. |
| Timers | No quiz timer. Do not restore running Study/Focus countdowns; persisted Focus completion is separate from countdown state. |
| Visual direction | Familiar interactive learning flow with original JavaFX components styled like Puppy AI; no external product assets or code. |
| Failures | Surface database/generation/validation errors explicitly. Never silently reset a damaged database, report a failed save as successful, or substitute invented quiz content. |

### Target architecture and data ownership

- Preserve JavaFX -> application services -> repositories/infrastructure.
  `ServiceRegistry` owns database lifetime, repositories and service wiring;
  controllers never issue SQL, construct providers or parse model JSON.
- Add `database/` for connection lifecycle and versioned transactional schema
  migrations, and `repository/` for JDBC storage. Use one serialized database
  execution path rather than sharing a mutable JDBC connection across the four
  existing workers. Enable foreign keys and bound lock waits. Do not hold a
  database transaction while waiting for AI or extracting documents.
- Introduce durable conversation/message/quiz identities and presentation-neutral
  history models. Keep provider `ChatMessage` separate. Use typed chat entries
  for text versus interactive quiz references so reopening history reconstructs
  controls rather than flattening a quiz into Markdown.
- Logical storage: conversations, ordered messages/entries, conversation
  attachments, Focus classes/tasks/preferences/current plan/sessions, non-secret
  settings, quiz definitions/questions/options. Keep ordering and relationships
  explicit, including quiz placement in chat. No attempt/progress/score tables.
- Persist extracted attachment text plus its filename/type/page/truncation
  metadata; a source path is metadata, never a dependency for restoration.
  Explain that this now stores private document text on disk; local SQLite is
  not encrypted by default. Credentials never enter database rows or logs.
- Move history ownership out of `ChatController` behind an application service.
  Save active chats, not just archived chats. Persist user messages, completed
  replies and cancellation/error snapshots at defined boundaries, not per token.
  Preserve visible partial replies as interrupted, never as completed replies.
  Serialize writes so stale snapshots cannot overwrite newer edits or chats.
- Keep Focus scheduling deterministic. Stage/validate a mutation, persist it
  transactionally and only then publish durable state; save failure must not
  leave the UI claiming success. Move affected mutation call sites to worker
  execution, not just AI imports. Restore saved historical sessions without
  reapplying new-session validation against today's date.
- Use a settings service for non-secret preferences and custom instructions;
  `ModelService` continues to own provider routing. Keep secret-file handling
  isolated. Import the legacy model choice once, record successful migration
  only after commit, and preserve the legacy file on migration failure.
- Add a `QuizService` with typed definitions and in-memory attempt state, an
  AI-backed generator through `ModelService.jsonChat`, and a strict parser.
  Grading/navigation/retry are pure Java and never require an AI call. Reuse
  existing document extraction and request-cancellation patterns.
- Quiz definitions are durable; attempt state belongs to the running app only.
  Switching chats must isolate attempts and stale generation callbacks by
  conversation/quiz ID. Normal Chat remains usable after quiz generation.
- Capture saved supplemental instructions at request start. Compose the fixed
  persona/application rules first and explicitly delimit lower-priority user
  preferences; blank instructions preserve current behavior. Do not pass them
  into Focus/Quiz structured generation. Prompt wording expresses precedence,
  not a guarantee that a probabilistic model cannot be influenced.

### Implementation work ledger

Statuses: **Not started**, **In progress**, **Blocked**, **Complete**.
Complete means implementation is persistent and the listed acceptance checks
have passed; record the actual validation command/result when closing an item.

| ID | Work item | Depends on | Status |
| --- | --- | --- | --- |
| DB-1 | SQLite JDBC dependency, connection lifecycle, serialized execution, schema/migrations and explicit startup errors | None | Complete - automated suite passed; manual smoke checks listed above remain |
| DB-2 | Non-secret settings repository/service, one-time legacy model-choice migration, persistent desktop preferences; retain secret file | DB-1 | Complete - atomic migration/rollback and preference tests passed |
| DB-3 | Durable history models/repository/service; save active/archived chats, message timing and extracted attachments; restore history | DB-1 | Complete - reopen/retry/UI history tests passed |
| DB-4 | Focus repositories, transactional mutations and full state restoration; adapt controller worker/UI handoff | DB-1 | Complete - persistence/rollback/worker tests passed |
| PROMPT-1 | Supplemental instructions service integration, Settings editor with Save/Reset, bounded composition and request snapshots | DB-2 | Not started |
| QUIZ-1 | Quiz models, structured request extraction/generation/parser, per-question difficulty and deterministic in-memory attempt logic | None | Not started |
| QUIZ-2 | Persist quiz definitions and typed history references; restore fresh unanswered attempts, retry without generation | DB-3, QUIZ-1 | In progress - definition schema/repository complete; runtime entry/card integration awaits quiz feature |
| QUIZ-3 | Multiple Quiz composer mode and Puppy-styled interactive Chat cards, navigation/feedback/score/review/retry | QUIZ-2 | Not started |
| VERIFY-1 | Cross-feature restart/failure/regression tests, manual local/cloud smoke checks and current-behavior documentation | DB-2, DB-3, DB-4, PROMPT-1, QUIZ-3 | Not started |

Suggested delivery order: DB-1 -> DB-2 -> DB-3 -> DB-4 -> PROMPT-1 ->
QUIZ-1 -> QUIZ-2 -> QUIZ-3 -> VERIFY-1. Dependencies in the ledger, not this
suggested sequence, determine what can proceed independently.

### Acceptance and verification gates

**Database and restart**

- Use temporary databases for automated tests: first launch, repeated startup,
  close/reopen, schema upgrade, foreign-key relationships and transaction rollback.
  A failed migration keeps existing data intact; unreadable, locked or damaged
  storage produces an actionable error rather than a fresh empty success state.
- Import an existing model preference exactly once without changing the separate
  API key or environment precedence. Restart restores model selection and
  always-on-top. A failed import is retryable and does not discard the old file.
- Restart restores the latest active chat as well as archived chats, correct
  message order, exact text/reply timing, attachments and quiz placement. Moving
  or deleting the original document does not break extracted context.
- Cancel/switch chats during streaming and while saves are queued: no cross-chat
  writes, lost completed messages or stale overwrites. Closing waits for accepted
  saves before closing storage; no reliance on daemon threads surviving exit.
- Restart restores Focus inputs/preferences/current plan and completion,
  including past sessions. Editing/completing/removing/clearing round-trip.
  Generate plan still replaces manual sessions as it does today. Timers do not
  resume or consume elapsed time while the app is closed.
- Database access is off the JavaFX thread; UI updates return through
  `Platform.runLater`. Injected save failures remain visible and do not leave
  partially committed service/UI state.

**Settings instructions**

- Editor supports multiline instructions, explicit Save and Reset to default
  (empty supplemental instructions). Persist across restart; save/reset failure
  is visible. Validate a documented finite size limit before storing/sending.
- Tests inspect actual outbound messages: unchanged fixed persona/document
  rules, exactly one leading system message, delimited supplemental preferences,
  existing attachment/history bounds, and unchanged output when preferences
  are blank. Both providers use the same composition.
- Saving affects the next request in existing/new chats, not a running request.
  Focus/Quiz JSON prompts are unaffected. Ensure Settings scrolls/fits on smaller
  screens when both the key section and instruction editor are visible.

**Multiple Quiz**

- Topic-only, attachment-only and combined requests work. Omitted setup gives
  exactly 5 Medium questions. Requests at 1 and 20 work; out-of-range or
  contradictory counts receive a clear correction request, not silent clamping.
  Support a uniform difficulty and explicit per-question/mixed difficulty;
  validate the returned count and requested difficulty allocation.
- Validate every generated definition before showing or saving it: nonblank
  questions/explanations, exactly four distinct nonblank options, exactly one
  valid correct option, valid difficulty and bounded payload. Invalid JSON,
  wrong counts, incomplete output and provider errors show a retryable failure;
  never present a partial or fabricated quiz as a completed generation.
- UI tests cover selection -> Check answer -> locked feedback, Previous/Next
  navigation, skipped questions, blocked final submission with unanswered items,
  deterministic score/review and fresh retry. No AI call for checking/review/retry.
- Save/reopen a chat and reconstruct its interactive card. Restart always clears
  every selection, score, feedback and navigation position while retaining quiz
  definitions. Verify database contents contain no attempts or user answers;
  do not accidentally persist them as generic chat messages or score summaries.
- Count/difficulty are supplied naturally in Chat, not a separate setup form.
  Requests can assign different difficulties to different questions. Existing
  Chat, Focus plan, Model, Help, Copy/Reuse and Markdown behavior remain intact.
- Follow existing cancellation behavior for generation on close/chat switch;
  late callbacks cannot attach a quiz to the wrong conversation. Test local and
  cloud generation manually without including private content or keys in logs.

Run the smallest relevant Maven test selectors per change, then `mvn test` for
the integrated result. Extend existing test files where applicable; new database
tests must use temporary storage rather than the user's real data directory.
In particular, the current `FxmlLayoutTest` constructs one registry with
`AppConfig.load()`; isolate that configuration before database-enabled UI tests.
Record automated results separately from manual UI/provider checks and mark
unavailable live-provider verification as pending, not passed.

### Explicitly deferred

Web research/Tavily, accounts/cloud sync, database encryption, search/export/
retention-management UI, timer recovery, Study preset changes, automatic Focus
quiz integration, manual-session preservation on plan regeneration, and unrelated
mascot/tray/packaging work are outside this feature set. Saving quiz attempts,
progress or scores is intentionally excluded, not a missing persistence task.

## Milestones

| Milestone | Scope | Status |
| --- | --- | --- |
| 1 | Project foundation and JavaFX desktop shell | ✅ Complete |
| 2 | Frontend interactions and UI | ✅ Complete |
| 3 | SQLite persistence for chat, Focus, settings and quiz definitions | Complete - storage delivery and automated checks above; interactive quizzes remain milestone 6 |
| 4 | AI integration (local Ollama + cloud OpenRouter chat, document attachments, Focus planner) | 🟡 In progress |
| 5 | Internet research and extraction | Pending |
| 6 | Study mode, settings, integration | Partial: Pomodoro/settings exist; supplemental instructions and Chat quizzes not started (PROMPT-1, QUIZ-1 through QUIZ-3) |
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
| `AiSettingsStore` | Persists model choice through SQLite, imports legacy selection once and keeps the OpenRouter key in its separate secret file; never used by controllers directly. |
| `Database` / repositories | Serialized JDBC transactions, schema lifecycle and durable chat/Focus/settings/quiz-definition storage. |
| `ChatHistoryService` | Owns durable chat snapshots, active-chat state, title updates, queued writes and failed-save retry. |
| `AppSettingsService` | Cached always-on-top preference; changes publish after a successful SQLite commit. |
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
- **Attachments:** extracted text and filename metadata persist locally. With a local model it
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
  `~/.local/share/PuppyAI`). SQLite stores chat, Focus, non-secret settings and
  quiz definitions there;
  `AiSettingsStore` keeps the API key in a separate secret file. The directory is
  configurable; runtime data should not be placed inside the project.
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
- **Focus sessions:** sessions and completion persist in SQLite. `Generate plan` rebuilds the
  scheduled list from classes and tasks; hand-made sessions are not carried into
  a regenerated plan. Manual and generated sessions can be edited in the Session
  tab, with placement re-validated on save. A running Focus countdown keeps the
  mascot in STUDYING and prevents the idle-sleep timer from firing.

## Known bugs / limitations

- The runtime uses separate numbered sprite frames, not a spritesheet. Legacy
  spritesheet guidance is no longer part of the current implementation plan.
- The entire 336 × 252 window captures mouse input, including transparent
  pixels; per-pixel hit testing is not part of the MVP.
- SQLite data and extracted attachments are not encrypted. Best-effort deletion
  cannot guarantee physical erasure from SSDs, filesystem snapshots or backups.
- History is loaded into memory at startup; pagination/search/retention controls
  are deferred. Large long-lived histories may require future paging work.
- Supplemental instructions and Multiple Quiz mode are not implemented; see
  PROMPT-1 and QUIZ-1 through QUIZ-3 above.
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

## Deferred UI ideas (not current commitments)

- Add study presets (e.g. 25/5 and 50/10), with custom durations later.
- Add search over persisted chat history; persistence itself is in scope above.
- Link manual sessions to source tasks so regeneration can keep them; persistent
  Focus state itself is in scope above.
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
11. Only extracted document text and filename metadata persist; original files
    stay where the user chose them. That text is sent to OpenRouter and its model provider
    only while a cloud model is selected; the local model keeps it on this
    computer.
12. The OpenRouter key is stored outside the repository in the user's data
    directory and is sent only in the `Authorization: Bearer` header. Error text
    is redacted and the UI never displays the key.
