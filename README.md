# VaderAI

A real-time AI copilot for interviews and study sessions. VaderAI runs as an always-on-top desktop overlay on Windows that listens to your call, watches your screen, and streams answers only you can see.

**Status:** v0.8.0 — all eight phases built. The overlay captures both audio channels, streams them to the backend, and renders a speaker-attributed transcript plus a streaming answer that fires on its own when the interviewer stops talking — grounded in a résumé, job description, and notes you supply. There is also a practice mode that runs a graded mock interview with no live call, and every session is kept for review and export afterwards. It now packages to a Windows installer and the backend containerises for deployment. Provider keys (Deepgram, Anthropic) are needed to run it against the real services.

---

## What it does

```
system audio + mic  →  streaming STT  →  live transcript
                                            ↓
screen capture (screenshots)  ──────→  Claude  →  answer streamed into the
                                                  overlay window
```

- **Dual-channel audio capture** — system audio (the other person) and your mic are captured as separate channels, so speaker attribution is exact without a diarization model.
- **Live transcript** with sub-second latency, attributed to _Interviewer_ or _You_.
- **Streaming answers** triggered automatically when the other side finishes a question, or manually via hotkey.
- **Screenshot Q&A** — capture a coding problem or a slide and ask about it.
- **Knowledge base** — your résumé, the job description, and personal notes ground every answer.
- **Practice mode** — mock interviews with graded feedback, no live call required.
- **Session history** — every session is kept: review the transcript with the answers in place, or export it to Markdown.
- **Excluded from screen sharing** — the overlay uses Windows' `WDA_EXCLUDEFROMCAPTURE`, enforced by the desktop compositor.

**Target:** first visible token within ~1.3–1.6 s of the question ending.

---

## Stack

| Layer          | Choice                                                             |
| -------------- | ------------------------------------------------------------------ |
| Desktop        | Electron ≥39 + React + TypeScript (`electron-vite`)                |
| Backend        | **Java 21 + Spring Boot 3.4**, Maven, Spring MVC + virtual threads |
| Transport      | WebSocket — binary audio frames up, JSON control + tokens down     |
| Auth + DB      | Supabase (JWT auth, Postgres)                                      |
| Speech-to-text | Deepgram Nova-3 streaming, 2-channel (OkHttp WebSocket)            |
| LLM            | Claude Opus 5 (`claude-opus-5`) via `anthropic-java`, streaming    |
| Packaging      | electron-builder (NSIS) + container image for the backend          |

Platform target for v1 is **Windows only**. All provider API keys live server-side; the desktop app never holds them.

**Why Spring MVC and not WebFlux:** the Anthropic Java SDK streams through a blocking `StreamResponse`, so a reactive stack would spend the project bridging blocking calls onto schedulers. Java 21 virtual threads give the same I/O concurrency with straight-line code.

---

## Repository layout

Polyglot monorepo — pnpm workspace for the TypeScript side, Maven for the backend:

```
VaderAI/
├── docs/               # Implementation plans and technical docs
├── contracts/          # Shared WS message fixtures, asserted by both test suites
├── packages/
│   └── protocol/       # zod schemas — source of truth for the wire format
├── apps/
│   ├── desktop/        # Electron overlay      (pnpm workspace member)
│   └── server/         # Spring Boot backend   (Maven — NOT a pnpm member)
└── supabase/           # Migrations (Supabase CLI owns the schema) — Phase 3
```

Inside `apps/desktop/src`: `main/` (Electron main — window, hotkeys), `preload/` (the
single `contextBridge` surface), `renderer/` (React overlay UI, audio capture,
session socket), `shared/` (types crossing all three).

Inside `apps/server/src/main/java/ai/vader/server`: `config/` (security, WebSocket),
`session/` (the `/v1/session` handler and per-connection state), `stt/` (provider
interface + Deepgram), `llm/` (the streaming answer engine and the blocking
schema-constrained one), `knowledge/` (the background documents), `practice/`
(mock interviews), `persistence/` (Spring Data JDBC), `protocol/` (records
mirroring the zod schemas).

### How an answer gets triggered

An answer fires on its own when channel 0 (the interviewer) produces a final
transcript segment and 700 ms of silence follows, with a hard 2 s debounce
between auto-asks. **Anything on the mic channel cancels it** — if you have
started answering, you do not need one generated. `Ctrl+Enter` asks regardless,
and `Ctrl+H` attaches a 1080p grab of the screen. A new trigger cancels an
answer still streaming.

The prompt is split at its cache boundary: system prompt and knowledge base go
in a cached prefix with a one-hour TTL, and the transcript goes after it.

### Background (the knowledge base)

**Background** in the overlay header holds three slots — résumé, job description,
notes. Paste text or upload a `.pdf`, `.docx`, `.txt`, or `.md`; extraction runs
server-side so the parsers stay on one platform. The screen shows the token
count and warns past 8,000, where the prefix starts costing more than it adds.

This is what separates _"at Acme I cut deploy time from 40 minutes to 6"_ from
generic advice, and it is the highest-leverage thing to fill in. It is read
**when a session starts**, so reconnect after editing — re-reading it mid-session
would change the cached prefix underneath a live conversation.

Note that Claude Opus 5 does not cache a prefix under 512 tokens. The system
prompt alone is ~306, so caching only engages once there is a real document in
here.

### Practice mode

**Practice** in the overlay header runs a mock interview against the job
description you saved: five questions, one at a time, answered out loud. Each
answer is scored 1–5 on **structure**, **specificity**, and **relevance**, with
one thing to fix and a rewrite of what you should have said. The closing report
names the themes that cost you most across the whole session.

It borrows the live pipeline wholesale — same socket, same speech-to-text, same
`transcript_turns` rows — so a practice run shows up in history like any other
session, tagged `sessions.kind = 'practice'`. Two things differ:

- **Only the mic is captured.** Channel 0 is left unconnected, which is digital
  silence, so there is no screen-share picker and the auto-ask path never arms.
  The answer text is the channel-1 transcript the overlay already receives.
- **Question generation, grading, and the report are blocking calls** with the
  response shape enforced by the API (`output_config.format`), not requested in
  the prompt. A half-parsed grade is worthless, so there is nothing to stream.

All the calls in one run share a single cached prefix, so the five grades are
cache reads rather than five cold writes — subject to the same 512-token floor
as above.

| Method | Path                                  | Purpose                                |
| ------ | ------------------------------------- | -------------------------------------- |
| `POST` | `/v1/practice/{sessionId}`            | generate the question set (idempotent) |
| `GET`  | `/v1/practice/{sessionId}`            | read it back                           |
| `POST` | `/v1/practice/{sessionId}/{position}` | grade one spoken answer                |
| `GET`  | `/v1/practice/{sessionId}/report`     | scores, rewrites, weakest themes       |

Starting without a job description on file returns `409` and says so — there is
nothing to write questions from.

### Session history

**History** in the overlay header lists every past session with its date,
duration, and how much was in it. Opening one shows the transcript **with the
answers in place** — the two are stored separately and interleaved by timestamp,
so each answer sits after the question that prompted it. A screenshot answer is
labelled, because there is no spoken question above it to pair with.

Answers are recorded when their stream finishes. An answer cancelled mid-stream
is not kept: cancelling is deliberate — a new question makes the previous one
stale — so the half sentence that got replaced is not worth reviewing.

Practice sessions are reviewed through their questions and grades rather than the
transcript, since that is where a practice run's answers live. Reopening one
costs nothing; only the closing report calls the model, and history does not.

**Export Markdown** writes the session through a native save dialog. **Delete**
removes the session and, by database cascade, its transcript, its answers, and
any grades — it asks first.

| Method   | Path                | Purpose                                  |
| -------- | ------------------- | ---------------------------------------- |
| `GET`    | `/v1/sessions`      | the list, newest first, with counts      |
| `GET`    | `/v1/sessions/{id}` | one session with its turns and answers   |
| `DELETE` | `/v1/sessions/{id}` | delete, cascading to everything below it |

Note the plural. `/v1/session` (singular) is the WebSocket endpoint and is public
because it authenticates itself with its first frame; these are one character
away from it and are not.

### First run

A new install is walked through the three things it needs, in order: **sign in →
allow the microphone → add your background**. The mic step asks the OS directly
(the Permissions API only reports; it never prompts) and says what to do if
Windows has already refused. The background step is skippable — it is a quality
lever, not a requirement — and once skipped it stays quiet. Sign-in is not
skippable, because dismissing it would leave an app where nothing works and
nothing explains why.

### When something breaks

Errors from both processes are written to `%APPDATA%\VaderAI\logs\vaderai.log`,
rotated at 512 KB to a single `.log.1` sibling. Native crashes leave minidumps in
`%APPDATA%\VaderAI\Crashpad\reports`, and a renderer crash raises a dialog naming
the log path — the UI is gone at that point, so it cannot be shown in the overlay.

**Nothing is uploaded.** A tool that sits on top of a job interview and listens to
both sides of it is the last thing that should be posting stack traces, which
routinely carry message text, to a third party.

### Security model

The desktop app signs in with Supabase and holds only a user access token; every
provider secret stays on the server. The WebSocket handshake cannot carry an
`Authorization` header, so the client sends `hello` with that token as its first
frame and the server closes `1008` if a valid one does not arrive within 5 s.

The backend connects to Postgres with the service role, which **bypasses RLS**.
Row-level policies protect the client-direct path only — on the server path,
every repository call takes the user id from the verified JWT, and
`UserScopingTest` is what proves it.

---

## Hotkeys

| Hotkey               | Action                            |
| -------------------- | --------------------------------- |
| `Ctrl+\``            | show / hide overlay               |
| `Ctrl+Enter`         | ask now (manual trigger)          |
| `Ctrl+H`             | screenshot + ask about the screen |
| `Ctrl+Shift+↑/↓/←/→` | move overlay                      |
| `Ctrl+Shift+C`       | clear answer panel                |

Hotkeys another app already owns are logged at startup rather than failing
silently.

---

## Getting started

**Local toolchain:**

- JDK **21** (`JAVA_HOME` must point at it — Maven follows `JAVA_HOME`, not `PATH`)
- Maven 3.9+ (or just use the committed `mvnw` wrapper)
- Node 22+ and pnpm 10+ (via `corepack`)

**Accounts and keys:**

- Supabase — or run the whole stack locally with the CLI (below); no account needed
- Deepgram API key — required for real transcription (Phase 3)
- Anthropic API key — required for answers (Phase 4)

```bash
pnpm install          # TypeScript side
cp .env.example .env  # fill in; the local-stack defaults already work

pnpm supabase:start   # Postgres + Auth in Docker, prints the keys to put in .env
pnpm supabase:reset   # (re)apply supabase/migrations

pnpm dev:server       # Spring Boot on :8787
pnpm dev:desktop      # Electron overlay
pnpm dev              # both

pnpm typecheck        # tsc across the workspace
pnpm test             # vitest (protocol + desktop)
pnpm test:server      # mvnw test
```

`pnpm supabase:start` runs only Postgres and Auth — the other services are
excluded because nothing here uses them and their images are large.

If it fails with `bind: An attempt was made to access a socket in a way forbidden
by its access permissions`, Windows has reserved a port range covering Supabase's
54321/54322 — Hyper-V grabs blocks of the dynamic range on boot. Check with
`netsh interface ipv4 show excludedportrange protocol=tcp`. Restarting the
`winnat` service as administrator releases them; the Java tests that need
Postgres cannot run until they are free.

The overlay is transparent and frameless, so on first launch look for it in the
top-left of your primary display — `Ctrl+Shift+↓/→` moves it. The status pill
turns amber if this machine cannot hide it from screen capture.

### Checking the audio capture

**Start listening** merges system audio into channel 0 and the mic into channel 1
of a single 16 kHz PCM16 stream, in 100 ms / 6400-byte frames. **Dump 10s WAV**
writes the last ten seconds to `Downloads/vaderai-capture.wav`; open it in
Audacity and the two channels should hold the two sources with no bleed.

An empty channel 1 usually means Windows' **default** recording device is not the
mic you are speaking into — virtual mixer endpoints often sit at digital silence.
The capture always follows the system default and re-acquires when it changes.

---

## Packaging and deployment

```bash
pnpm package:desktop   # NSIS installer + portable .exe -> apps/desktop/release/
pnpm package:server    # backend container image
```

The desktop build is **unsigned**, so Windows SmartScreen warns on a machine that
has not seen it before — **More info → Run anyway**. `VITE_*` values are baked in
at build time, so an installer is permanently pointed at whatever backend was
configured when it was built; put production values in a gitignored
`.env.production` at the repo root.

The backend ships as a multi-stage Dockerfile and deploys to Fly.io, where TLS is
terminated at the edge — `wss://` on a real certificate with nothing to
configure. Provider keys live in `fly secrets` and never touch the client.

Full runbook, including the exact secrets and the clean-VM checklist:
[`docs/003-deployment.md`](docs/003-deployment.md).

**Versions:** `0.MINOR.PATCH`, `MINOR` being the last completed phase. The root
`package.json`, `apps/desktop/package.json`, and `apps/server/pom.xml` are
expected to agree.

---

## Documentation

| Path                                                                     | Contents                                                 |
| ------------------------------------------------------------------------ | -------------------------------------------------------- |
| [`docs/001-implementation-plan.md`](docs/001-implementation-plan.md)     | Architecture, latency budget, cost model, risk register  |
| [`docs/002-implementation-phases.md`](docs/002-implementation-phases.md) | Phase-by-phase build checklist — **start here**          |
| [`docs/003-deployment.md`](docs/003-deployment.md)                       | Building the installer, deploying the backend, releasing |

---

## Scope boundary

VaderAI implements the assistant and uses Electron's documented `setContentProtection` API so the overlay is excluded from screen shares — the same OS call password managers and banking apps use.

It does **not** implement anti-proctoring evasion: defeating proctoring software, process-name spoofing, or kernel-level hooks are out of scope and will not be added.
