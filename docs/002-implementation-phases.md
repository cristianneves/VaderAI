# 002 — MVP Implementation Phases

**Status:** approved — all ten phases built (see each exit criterion for what is still a manual check; Phase 8's needs a clean VM and a Fly.io account)
**Scope:** Windows-only MVP — working app, no billing
**Backend:** Java 21 + Spring Boot 3.4 (Maven)
**Total estimate:** ~12–16 days

Eight phases to the MVP, plus Phase 9 for competitive parity. Each has a hard exit criterion — do not start the next phase until the current one passes it. The ordering is driven by risk, not convenience: the two things most likely to fail (system-audio loopback, capture-invisible overlay) are proven in Phases 1 and 2, before anything is built on top of them.

Architecture, backend stack rationale, cost model, and risk register live in [`001-implementation-plan.md`](001-implementation-plan.md). This document is the execution checklist.

---

## Dependency graph

```
Phase 0  Foundations (pnpm workspace + Maven/Spring Boot)
    │
    ├──────────────┬───────────────────────┐
    ▼              ▼                       ▼
Phase 1        Phase 2                 Phase 3
Overlay Shell  Audio Capture           Backend + STT
(TypeScript)   (TypeScript)            (Java)
    │              │                       │
    └──────────────┴───────────┬───────────┘
                               ▼
                          Phase 4  Answer Engine
                               │
                    ┌──────────┼──────────┐
                    ▼          ▼          ▼
               Phase 5     Phase 6    Phase 7
               Knowledge   Practice   History
               Base        Mode       + Review
                    └──────────┼──────────┘
                               ▼
                          Phase 8  Packaging
                               │
                               ▼
                          Phase 9  Competitive parity
```

**Parallelizable:** Phases 1, 2, and 3 are independent after Phase 0 — and now split cleanly by language, so a second person can take the Java track (Phase 3) while the first does the Electron track (Phases 1–2). Solo, run them in the order given; Phase 2 carries the most risk and must not be deferred. Phases 6 and 7 are independent of each other, but Phase 6 turned out to need Phase 5 — a mock interview has nothing to ask about without the stored job description.

---

## Phase 0 — Foundations

**Goal:** an empty but correctly wired polyglot monorepo that starts both apps.
**Estimate:** 1 day · **Risk:** none

### TypeScript side

- [x] `pnpm-workspace.yaml` with `apps/desktop` and `packages/protocol` (the Java server is **not** a pnpm member)
- [x] Root `package.json` — scripts, `onlyBuiltDependencies` for electron/esbuild (pnpm 10 blocks postinstall by default, and Electron needs its binary download)
- [x] `apps/desktop` — `electron-vite` + React + TypeScript, Electron pinned **≥39**
- [x] `packages/protocol` — zod schemas, built with `tsc` to `dist/`
- [x] `tsconfig.base.json`, ESLint flat config, Prettier

### Java side

- [x] `apps/server`: Java **21**, Maven, Spring Boot 3.4.x
- [x] Starters: `web`, `websocket`, `validation`, `actuator` — only what is needed to boot

> `security`, `oauth2-resource-server`, `data-jdbc`, the PostgreSQL driver, and
> `com.anthropic:anthropic-java` moved to the phases that configure them (3 and 4).
> Adding `spring-boot-starter-security` here would put HTTP Basic on every route
> and 401 the health endpoint — Phase 0's own exit criterion — forcing a
> throwaway `permitAll` config that Phase 3 would delete.

- [x] Commit the Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/`)
- [x] Pin the toolchain in `pom.xml` (`<java.version>21</java.version>`) — `java` on PATH here is 23 while Maven follows `JAVA_HOME`; do not let them diverge silently
- [x] `application.yml`: port 8787, `spring.threads.virtual.enabled: true`, Flyway/Liquibase **off**
- [x] Health endpoint responding at `/actuator/health`

### Shared

- [x] `contracts/messages/*.json` — one fixture per WebSocket message type
- [x] `.gitignore` covering Node, Electron, **Maven/Java**, and Windows
- [x] `.env.example`, `.editorconfig`, `.nvmrc`
- [x] GitHub config: CI workflow, issue templates, Dependabot (a PR template was
      added here too, then dropped in Phase 7 — PR bodies are written freehand)

### Exit criterion

`pnpm dev:server` starts Spring Boot on :8787 with `/actuator/health` returning `UP`; `pnpm dev:desktop` opens an Electron window rendering a placeholder. `pnpm typecheck` and `mvnw test` both pass clean.

**Met.** `mvnw test` asserts `/actuator/health` returns `UP` on a random port; `pnpm typecheck` is clean; the Electron window renders.

---

## Phase 1 — Overlay shell

**Goal:** a transparent, always-on-top window that is invisible to screen capture and driven entirely by hotkeys.
**Estimate:** 1 day · **Risk:** low · **Depends on:** Phase 0 · **Language:** TypeScript

### Tasks

- [x] `BrowserWindow`: `frame: false`, `transparent: true`, `alwaysOnTop: true`, `skipTaskbar: true`, `focusable: false`
- [x] `win.setContentProtection(true)` → `WDA_EXCLUDEFROMCAPTURE` on Windows
- [x] `win.setAlwaysOnTop(true, 'screen-saver')` so it floats above full-screen meeting apps
- [x] Detect Windows build at startup; warn if < 10 build 2004 (overlay renders **black** in captures instead of vanishing)
- [x] Register global hotkeys; unregister all on `will-quit`
- [x] Preload `contextBridge` — no `nodeIntegration`, no remote module
- [x] Overlay UI shell: transcript pane, answer pane, status pill, drag handle

`focusable: false` is what stops the overlay from stealing focus from the meeting window. Easy to miss, very visible when wrong.

### Hotkeys

| Hotkey               | Action                            |
| -------------------- | --------------------------------- |
| `Ctrl+\`             | show / hide overlay               |
| `Ctrl+Enter`         | ask now (manual trigger)          |
| `Ctrl+H`             | screenshot + ask about the screen |
| `Ctrl+Shift+↑/↓/←/→` | move overlay                      |
| `Ctrl+Shift+C`       | clear answer panel                |

### Exit criterion

Join a Meet call from a second device and share your entire screen. The overlay is **fully absent** from the shared view on the second device while visible on yours. `Ctrl+\` toggles it; clicking the meeting window never loses focus to the overlay.

**Not yet verified — this is a two-device manual test.** Everything it depends on is in place: the window flags, `setContentProtection(true)`, the `screen-saver` always-on-top level, and the build check (this machine is build 26200, well above 19041). Run the test before starting Phase 2.

---

## Phase 2 — Audio capture

**Goal:** one 16 kHz 2-channel PCM16 stream — channel 0 = system audio, channel 1 = mic.
**Estimate:** 1–2 days · **Risk: HIGHEST — do not defer** · **Depends on:** Phase 0 · **Language:** TypeScript

This is the foundation of the entire product. If loopback capture doesn't work, nothing else matters.

### Tasks

- [x] Pin Electron **≥39** (native loopback; below 39 it silently does not work)
- [x] Main: `setDisplayMediaRequestHandler` returning `{ video: screen, audio: 'loopback' }`
- [x] Renderer: `getDisplayMedia({ video: true, audio: true })`, then **immediately stop and remove every video track** — we want audio only, and a live video track burns GPU for nothing
- [x] Renderer: `getUserMedia` for mic with `echoCancellation: false`, `noiseSuppression: true`
- [x] Route both into one `AudioContext` via `ChannelMergerNode` — ch0 = system, ch1 = mic
- [x] `AudioWorkletProcessor`: resample to 16 kHz, interleave, emit PCM16 in 100 ms frames (**6400 bytes** each) — resampling is done by asking for a 16 kHz `AudioContext`, and the interleave/PCM16 step sits next to the worklet rather than inside it so it can be unit tested; frames on the wire are unchanged
- [x] Device-change and stream-end handling; reacquire on default-device switch
- [x] Debug utility: dump N seconds of captured PCM to a WAV on disk

The 2-channel merge makes speaker attribution exact and free downstream — no diarization model, no heuristics. Anything that flattens this to mono destroys attribution in Phase 3.

### Exit criterion

Play a YouTube video in a browser while speaking into the mic. Dump 10 s to WAV, open in Audacity: channel 0 contains only the video, channel 1 contains only your voice, no bleed.

**Partly verified — the speaking half needs you.** Automated checks on this machine:
a 440 Hz tone played through the speakers was captured on channel 0 at 16 kHz /
2 ch / 16-bit, 85 frames over 8.5 s (exactly 10 frames per second, 6400 bytes
each), with channel 1 at digital zero — so system audio does not bleed into the
mic channel. Feeding two known tones through the real merger and worklet gives
440 Hz only on ch0 and 1000 Hz only on ch1, with zero energy at the other
frequency in each.

Not covered: real mic content on channel 1. **This machine's default input device
is `fifine - Microphone (fifine Virtual Audio Device)`, a virtual mixer endpoint
that currently outputs near-silence** (peak 6.6e-6, below the PCM16 floor) — the
physical capsule is a different device, `Microfone (fifine Microphone)`. The mic
track itself is live and flowing. If the dump shows an empty channel 1, change
the Windows default recording device before assuming the capture is broken.

---

## Phase 3 — Backend and transcription

**Goal:** authenticated WebSocket session producing a live, speaker-attributed transcript.
**Estimate:** 3 days · **Risk:** medium · **Depends on:** Phase 0 · **Language:** Java

### Tasks

- [x] Supabase schema + migrations: `profiles`, `sessions`, `transcript_turns`, `knowledge_docs`; RLS on every table
- [x] Supabase Auth in the desktop app (email/password); persist the access token
- [x] `SecurityConfig` — OAuth2 resource server against the Supabase JWKS endpoint
- [x] `WebSocketConfig` — register `/v1/session`, raise text and binary buffers to **64 KB**, wrap sessions in `ConcurrentWebSocketSessionDecorator`
- [x] `SessionWebSocketHandler extends AbstractWebSocketHandler` — `handleBinaryMessage` for audio, `handleTextMessage` for control JSON
- [x] **First-frame auth:** client sends `hello` with the access token as its first message; close `1008` if no valid token within 5 s (the WebSocket API cannot set an `Authorization` header — see 001)
- [x] `SttProvider` interface: `start` / `write` / `onTranscript` / `close`
- [x] `DeepgramSttProvider` over OkHttp `WebSocket` — **`SessionWebSocketHandler` must never reference Deepgram types directly**
- [x] Deepgram params: `model=nova-3&encoding=linear16&sample_rate=16000&channels=2&multichannel=true&interim_results=true&endpointing=700&punctuate=true`
- [x] Map `channel_index[0]` → `0 = interviewer`, `1 = user`
- [x] Relay interims to the client; buffer finals in a ring buffer for the LLM
- [x] Batch-persist finalized turns via Spring Data JDBC — **never per-word**
- [x] Scope every query by the JWT `userId` (service role bypasses RLS — see 001)
- [x] Reconnect and backpressure handling on both the client socket and the Deepgram socket

Three Spring specifics that cost hours if discovered late — buffer size, concurrent sends, and handshake auth — are covered in [001 § Three backend traps](001-implementation-plan.md#three-backend-traps-to-design-around). Read that section before starting this phase.

### Exit criterion

Speak into the mic while a podcast plays. Both channels appear in the overlay transcript within ~300 ms, correctly attributed to **Interviewer** and **You**, with no crossover. Killing the network and restoring it reconnects without losing the session. A cross-user read test fails as expected.

**Met except for live Deepgram, which needs an API key.** Verified end to end on the
local Supabase stack: a real sign-up mints an ES256 access token, the `hello`
frame is validated against the real JWKS, the server answers `ready` with a
session id, a 6400-byte audio frame is forwarded to the STT socket, and the
results come back as `transcript` messages attributed to channel 0 and channel 1.
Postgres afterwards holds the session row closed and **exactly the two finals** —
the interim was relayed but not stored.

The STT socket in that run was a local stand-in speaking Deepgram's frame format
(`DEEPGRAM_URL` points the provider at it). Everything up to and including the
Deepgram wire contract is exercised; what is unproven is Deepgram's own
transcription quality and latency, which is what the ~300 ms and "no crossover"
half of this criterion is really about. **Set `DEEPGRAM_API_KEY` and re-run to
close it.**

Cross-user reads: covered by `UserScopingTest`, which runs against the real
Supabase Postgres and asserts that asking for another user's session returns
nothing.

---

## Phase 4 — Answer engine

**Goal:** the core loop — detect an ended question, stream an answer into the overlay.
**Estimate:** 2–3 days · **Risk:** medium · **Depends on:** Phases 1, 2, 3 · **Language:** Java + TypeScript

### Tasks

- [x] `TurnDetector` — auto-ask when channel 0 emits a final segment **and** 700 ms of silence follows
- [x] Hard debounce: no auto-ask within 2 s of the previous one
- [x] Manual trigger on `Ctrl+Enter` using whatever is buffered
- [x] `PromptAssembler` — system prompt + knowledge base into a cached prefix; volatile turn context **after** the breakpoint
- [x] `AnswerEngine` interface + `AnthropicAnswerEngine` using `client.messages().createStreaming(...)`
- [x] `OutputConfig.Effort.LOW` as the latency lever; leave thinking on (default on Opus 5)
- [x] `CacheControlEphemeral` with `Ttl.TTL_1H` on the knowledge-base block
- [x] Fast mode behind a config flag, **off by default** — beta path: `client.beta().messages()` + `AnthropicBeta.FAST_MODE_2026_02_01` + `Speed.FAST`
- [x] Stream `text_delta` events to the client as `answer_delta` messages; render token-by-token
- [x] Screenshot path on `Ctrl+H`: `desktopCapturer` → PNG → **downsample to 1080p** → base64 → image block placed _before_ the text block
- [x] Cancel-in-flight: a new trigger closes the previous `StreamResponse`
- [x] Log `usage` per answer: input, output, `cacheReadInputTokens`

**Verify SDK builder names against the [anthropic-java](https://github.com/anthropics/anthropic-sdk-java) repo before writing this code** — especially the image content block, which is not documented in the plan. Do not infer Java bindings from the REST shape.

### Exit criterion

Play a recorded interview question through speakers. First token renders in **under 1.6 s** from the end of the question, and the answer is on-topic. Screenshot a LeetCode problem, press `Ctrl+H`, get a correct approach with complexity analysis. Logs show non-zero `cacheReadInputTokens` from the second answer onward.

**Loop verified; latency and answer quality need a real key.** End to end against a
local stand-in for the Messages API: a `hello` on a real Supabase JWT, an
interviewer question on channel 0, `answer_start` fired **739 ms** after the
question's final segment (the 700 ms silence window, on the nose), three
`answer_delta` messages, `answer_end`, and usage logged. The request carried
`model=claude-opus-5`, `effort=low`, and a `cache_control` of
`{"type":"ephemeral","ttl":"1h"}` on the last system block, with the transcript
**outside** the cached prefix. The `Ctrl+H` path arrives upstream as
`image -> text`, base64, `image/png`.

Not covered: real first-token latency and whether the answers are any good.
Both need `ANTHROPIC_API_KEY`.

> ⚠️ **The cache half of this criterion cannot pass yet, and it is not a bug.**
> Claude Opus 5 will not cache a prefix below **512 tokens**, and the system
> prompt alone is ~306. With the knowledge base still empty, `cacheReadInputTokens`
> stays 0 no matter how many answers you run — the breakpoint is sent and simply
> ignored. Phase 5 pushes the prefix over the minimum (a résumé alone clears it),
> and that is the point to re-check this line.

---

## Phase 5 — Knowledge base

**Goal:** answers grounded in the user's actual background.
**Estimate:** 1–2 days · **Risk:** low · **Depends on:** Phase 4

### Tasks

- [x] Settings screen: upload/paste résumé, job description, free-form notes
- [x] Extract text from PDF and DOCX server-side (Apache PDFBox + Apache POI)
- [x] Store in `knowledge_docs`, scoped per user
- [x] Inject into the cached system prompt block on session start
- [x] Show token count and warn above a sane ceiling (~8k)
- [ ] Verify the cache still hits after a knowledge-base edit (new prefix → one cold write, then reads) — **needs a real key; the prefix change itself is covered by a test**

This is what separates _"at your role at Acme you cut deploy time from 40 to 6 minutes"_ from generic advice — the highest-leverage feature in the product.

### Exit criterion

Upload a résumé, ask a behavioral question, and the answer cites a specific project from it by name.

**Plumbing verified; whether the answer cites the project needs a real key.**
End to end against the local stack: a pasted résumé stored (`204`), notes
uploaded as a file and extracted server-side (`200`, text intact through UTF-8),
an `image/png` upload refused with `415` rather than stored as binary noise, and
the assembled block read back with a token count. Triggering an answer then put
the résumé **and** the notes in the cached system prefix, with neither leaking
into the volatile half.

**This also clears the Phase 4 caching blocker.** The cached prefix measured
~530 tokens with a one-page résumé, over Claude Opus 5's 512-token minimum — so
prompt caching can now actually engage, where in Phase 4 the breakpoint was sent
and ignored. Note it is only just over: a very short résumé plus the ~306-token
system prompt could still fall under the line.

Two things are database-enforced rather than trusted to the service: one document
per kind per user (a unique index), and cross-user isolation (`KnowledgeServiceTest`
asserts one user's `assemble` never returns another's text).

---

## Phase 6 — Practice mode

**Goal:** mock interviews with graded feedback, no live call.
**Estimate:** 1–2 days · **Risk:** low · **Depends on:** Phases 4 and 5 (the stored job description)

Same STT and LLM pipeline, different orchestration. Cheap to build, and it is the version of the product you can show anyone.

### Tasks

- [x] Generate a question set from the stored job description
- [x] Present one question at a time; record the spoken answer via the mic channel only
- [x] Grade each answer against the job description — structure, specificity, relevance
- [x] Session report: per-question scores, concrete rewrites, weakest themes
- [x] Persist practice sessions in history

Orchestration is REST (`/v1/practice/**`); audio keeps flowing over the existing
`/v1/session` socket, so **the protocol is unchanged** — no new message type, no
new fixture, no contract-test edit on either side. The client builds each answer
from the channel-1 `transcript` messages it already receives.

Two notes on the design, both deliberate:

- **Nothing in the live path needed changing.** `TurnDetector` arms only on
  channel-0 finals, and channel 0 is silent in practice mode, so the auto-ask
  path never fires without being suppressed.
- **Grading needs a blocking, structured call**, which `AnswerEngine` does not
  do — it only streams. `JsonEngine` is the second port, with the response shape
  enforced by the API rather than requested in the prompt.

### Exit criterion

Complete a 5-question mock interview and receive per-question feedback that identifies a real weakness in a deliberately vague answer.

**Plumbing verified; whether the feedback is any good needs a real key.** End to
end against the local stack: a real sign-up minted an ES256 token, the session
socket answered `ready`, and `POST /v1/practice/{sessionId}` **without** a stored
job description returned `409` with an actionable message rather than an empty
question set. With one stored, five questions came back, a retry returned the
same five (one model call, not two — the unique index on
`(session_id, position)` backs that up), a deliberately vague answer graded
2/1/4 with a rewrite, and the report returned two themes. Postgres afterwards
held `sessions.kind = 'practice'` and exactly five `practice_questions` rows,
one graded. A second user asking for that report got `404`.

The requests the server actually sent were checked on the wire: all three carried
a **byte-identical** cached prefix with the 1-hour breakpoint on the last system
block, `output_config.format.type = "json_schema"` with the schema, `max_tokens`
4096 (not the streaming engine's 1024 — the budget covers thinking too), and no
`effort` override. The vague answer appeared in the user turn and **not** in the
cached prefix.

Not covered, and worth being plain about:

- **Whether the grading is actually useful.** The model was a local stand-in
  replaying canned JSON, so what is proven is the orchestration and the request
  shape, not the judgement. Set `ANTHROPIC_API_KEY` and re-run the
  deliberately-vague-answer case to close this.
- **The mic-only capture path.** `AudioCapture.start` has no test — the node test
  environment has no `AudioContext` — so `{ micOnly: true }` inherits that gap.
  Leaving merger input 0 unconnected should give digital silence on channel 0
  while the merger still declares two output channels, which is what keeps the
  worklet's `input.length < 2` guard and the 6400-byte frame format intact. That
  follows from the Web Audio spec but has not been run: **check that channel 0 is
  silent and no screen-share picker appears** during the manual run.

> ⚠️ **The caching win is real but conditional, same as Phase 5's.** With the
> one-line test documents the cached prefix measured only ~260 tokens — well
> under Claude Opus 5's 512-token minimum, so the breakpoint was sent and
> ignored. The practice system prompt is ~190 tokens, shorter than the live one's
> ~306, so it leans harder on the documents than the live path does. A real job
> description plus a one-page résumé clears the floor comfortably; a two-line job
> description will not.

---

## Phase 7 — Session history and review

**Goal:** nothing is lost when the call ends.
**Estimate:** 1 day · **Risk:** low · **Depends on:** Phase 4

### Tasks

- [x] REST endpoints: list sessions, fetch one with turns, delete
- [x] Session list with date, duration, question count
- [x] Review screen: full transcript paired with the answers given
- [x] Export a session to Markdown
- [x] Delete cascades to turns

**The estimate was wrong about where the work was.** Three of these five were
straightforward; the phase was really about a gap the plan had not noticed.

**Answers were never persisted.** Through Phase 6, `LiveSession.ask` forwarded
each delta to the socket and dropped it — no buffer, and `onComplete` only logged
token counts, so an answer existed on screen and nowhere else. "Review the full
transcript **and answers**" was therefore unreachable without a new write path,
which is the bulk of what this phase actually did. Three notes on how:

- **Answers could not go in `transcript_turns`.** Its `channel` column mirrors
  Deepgram's channel index and is constrained to `(0, 1)` — interviewer and mic.
  Widening it to fit an assistant turn would blur the speaker attribution the
  whole pipeline rests on, so answers got their own table.
- **Only completed answers are stored.** Cancelling is deliberate — a new
  question makes the previous one stale — so a half sentence that was replaced on
  purpose does not reach the history, and a failed answer was never given.
- **The accumulator appends before sending.** A send that fails on a slow client
  shortens what the user saw, not what was stored.

**"Delete cascades to turns" needed no code at all** — the `on delete cascade`
clauses have been there since Phase 3. What it needed was a test asserting them
against the database rather than through the repositories, which would pass even
if the service were deleting rows by hand.

### Exit criterion

Close a session, reopen the app, and review the full transcript and answers.

**Met.** End to end against the local stack, with a real Supabase sign-up and the
real session socket: two transcript turns and two answers (one `Ctrl+Enter`, one
`Ctrl+H`) went in, the socket closed, and Postgres held both answers with their
triggers. `GET /v1/sessions` then reported the session as `live` with
`turns=2 / answers=2 / practiceQuestions=0` and a non-null `endedAt`;
`GET /v1/sessions/{id}` returned the turns and answers, which interleave by
timestamp into `Interviewer → You → Answer (manual) → Answer (screenshot)`;
`DELETE` returned `204` and left zero sessions, turns, and answers.

Negatives all held: `401` with no token, `404` for an unknown id, `404` on a
second delete, and a second user got `[]` from the list plus `404` on both detail
and delete — with their delete leaving all rows intact.

Two things are worth recording because they are not obvious from the code:

- **`/v1/sessions` is one character from a public route.** `/v1/session`
  (singular) is `permitAll` because the WebSocket authenticates itself with its
  first frame. Spring matches that on the exact path, so the plural routes are
  protected — but `VaderAiApplicationTests` now pins it rather than leaving it to
  inference.
- **A practice session is reviewed through `GET /v1/practice/{id}`, not the
  transcript.** Its spoken answers live on the question rows, and that route
  costs no model call — where the `/report` route would re-bill the themes on
  every view.

**Not covered: the save dialog.** `dialog.showSaveDialog` cannot be exercised in
the node test environment, so `main/export.ts` has no test — the same gap
`screenshot.ts` and `display-media.ts` already have. What _is_ verified: the call
shape typechecks against Electron's own definitions, and the app builds and
launches with the handler registered. What is **not**: that the dialog behaves at
runtime. It is deliberately unparented — the overlay is `focusable: false`, and a
dialog modal to a window that cannot take focus is how you get one stuck behind
an always-on-top window — but whether that holds on Windows needs a human at the
keyboard. **Open the History panel, pick a session, and press Export Markdown.**

---

## Phase 8 — Packaging and release

**Goal:** an installable `.exe` plus a deployed backend.
**Estimate:** 1–2 days · **Risk:** medium — first-run issues surface only here · **Depends on:** all

### Tasks

- [x] `electron-builder` NSIS target: installer + portable variants
- [x] App icon, product metadata, version scheme
- [x] Backend: multi-stage Dockerfile (Buildpacks not used — see below)
- [x] Deploy the container (Fly.io / Railway / Render); WSS with a real certificate — **`fly.toml` written and the image verified locally; the deploy itself needs your account**
- [x] Point the desktop build at the production backend URL via build-time env
- [x] First-run flow: sign in → grant mic permission → knowledge-base prompt
- [x] Crash and error reporting
- [x] Windows build check with an actionable message on unsupported versions
- [ ] Smoke-test on a **clean** Windows VM with no dev tools installed — **needs a VM and a human**

The deploy runbook — Fly commands, the exact secrets, the SmartScreen behaviour,
and the VM checklist — is [`003-deployment.md`](003-deployment.md).

**A multi-stage Dockerfile rather than Buildpacks.** `spring-boot:build-image`
would have been fewer lines, but it needs a Docker daemon reachable from Maven
during the build and produces a ~600 MB image whose contents we do not control.
The Dockerfile is explicit, pins the JRE, and drops to a non-root user. It also
uses the `maven` image instead of the committed `./mvnw` — the wrapper is a shell
script, and a Windows checkout can carry CRLF endings that a Linux shell refuses
to run.

Three things worth recording:

- **Versions now move together.** `0.MINOR.PATCH` with `MINOR` as the last
  completed phase, so this is `0.8.0` in the root `package.json`,
  `apps/desktop/package.json`, and `apps/server/pom.xml`.
- **`productName` had to be set explicitly.** Electron derives `app.getName()` —
  and therefore `userData` — from `productName`, falling back to `name`. Without
  it the installed app kept its session and logs under
  `%APPDATA%\@vaderai\desktop`, a directory named after the npm scope. It is
  `%APPDATA%\VaderAI` now, which also means **a dev install signed in before this
  change will look signed out**: its session is in the old directory.
- **`@vaderai/protocol` is bundled into the preload rather than externalised.**
  Left external it would have to resolve out of a pnpm symlink at runtime from
  inside the asar, which is the classic way a packaged Electron app dies with
  "Cannot find module" on a machine that has no workspace. With it inlined the
  asar holds `out/` and a `package.json` and nothing else — verified, zero
  `node_modules` entries.

### Exit criterion

Install the `.exe` on a clean Windows VM, sign in, join a call, and get a working answer — with no developer tooling present on that machine.

**Not met — this one genuinely needs a clean VM, and nothing here substitutes for
it.** What _is_ verified, on this machine:

- **The installer and the portable `.exe` build and are correct.**
  `VaderAI-0.8.0-setup.exe` and `VaderAI-0.8.0-portable.exe`, 90 MB each. The
  `.exe` carries ProductName `VaderAI`, version `0.8.0`, the copyright string,
  and the generated icon. The asar contains exactly `out/**` plus `package.json`
  — **zero** `node_modules` entries — and the preload has `PROTOCOL_VERSION`
  inlined with `electron` as its only external import.
- **The packaged app runs.** Launched from `win-unpacked`, it stayed up with its
  five processes and a window titled VaderAI, and created
  `%APPDATA%\VaderAI` with `Local Storage` and a `Crashpad/` directory — the
  latter being proof the crash reporter is actually running, not just configured.
- **The container is real.** Built from the Dockerfile, it runs as
  `uid=10001(vaderai)`, answers `/actuator/health` with `{"status":"UP"}` and
  HTTP 200, returns **401** on `/v1/sessions` without a token, and completes a
  WebSocket upgrade on `/v1/session` with **101**. 403 MB. It also **fail-fasts
  with no database** rather than starting half-alive, which is the behaviour you
  want when `SUPABASE_DB_URL` is wrong.
- **Both suites pass.** 169 TypeScript tests (32 new: the first-run resolver, the
  error log, the URL derivation, the capture notice) and 126 Java tests against a
  real Supabase Postgres.

Not covered, and worth being plain about:

- **The clean-VM run itself**, which is the whole criterion. Nothing above proves
  the app behaves on a machine without the Visual C++ runtimes, without a
  developer's audio devices, and with SmartScreen in the way.
- **The deploy.** `fly.toml` has never been applied to a real Fly account, so the
  secrets, the health check timing, and the Supabase connection from Fly's
  network are all unproven.
- **The reporter's wiring.** `ErrorLog` is tested directly against the filesystem
  and `captureProtectionNotice` against its message, but the `uncaughtException`
  and `render-process-gone` handlers that call them have no test — the same gap
  `export.ts`, `screenshot.ts`, and `display-media.ts` already have. The Crashpad
  directory appearing at runtime is the strongest evidence available without
  deliberately crashing the app.
- **Code signing.** Skipped by decision. Expect _"Windows protected your PC"_ on
  the VM and take the **More info → Run anyway** path.

---

## Phase 9 — Competitive parity

**Goal:** close the four gaps that a side-by-side demo against Cluely or
ParakeetAI would lose on.
**Estimate:** 2–3 days · **Risk:** low · **Depends on:** all

Driven by [`004-competitive-analysis.md`](004-competitive-analysis.md), which
compared our tree at `0.8.0` against both products. Its conclusion: **we win on
the things that are hard to build — two-channel speaker attribution, the latency
budget, graded practice mode — and lose on the things that are cheap.** This
phase is the cheap half. Version moves to `0.9.0`.

The stealth features ParakeetAI advertises (Task Manager, tab-switch, cursor,
proctoring) were evaluated in that document and **stay out of scope**, unchanged
from the line at the bottom of this file.

### 9a — Ask bar and follow-up memory

- [x] `ask.question` added to the protocol, optional so an old client still validates and `PROTOCOL_VERSION` stays `1`
- [x] `screenshot.note` stops being discarded — the field existed since Phase 4 and never reached the prompt
- [x] Prior exchanges replayed as real user/assistant turns, capped at **3**, entirely after the cache breakpoint
- [x] `Ctrl+K` opens a composer; quick actions ("What next?", "Shorter", "More detail", "Recap") are ordinary typed questions
- [x] Server `error` frames and the connection `detail` string reach the screen instead of being dropped

**The focus problem is the interesting part.** The overlay is `focusable: false`,
which is what stops it stealing focus from the meeting — and on Windows maps to
`WS_EX_NOACTIVATE`, so a text field receives no keystrokes. Focus is *borrowed*:
granted when the composer opens, handed back on submit or `Escape`
(`main/overlay-window.ts::setComposing`). Blur has to come **before** dropping
focusability, or Windows is left with no active window rather than returning to
the meeting.

Memory is capped at three because those turns sit after the breakpoint and are
billed in full on every subsequent question. `AnswerRequest.Exchange` carries a
*short* rendering of what was asked, not the transcript — the transcript already
travels in `conversation`, and repeating it per exchange would grow the request
quadratically over a session.

**Exit criterion:** `Ctrl+K`, type "explain that more simply", get an answer that
refers to the previous one. `Ctrl+H` with a note asks about that note. A failed
answer shows an error rather than silence.

**Met in the parts that do not need a key.** `AnthropicAnswerEngineTest` asserts
on the wire that two prior exchanges arrive as five messages —
user/assistant/user/assistant/user — with the live question last and nothing
remembered leaking into the system blocks. `PromptAssemblerTest` pins that a
typed question stays out of the cached prefix and that the prefix is byte-
identical with and without memory. **Whether follow-ups actually read as
follow-ups needs `ANTHROPIC_API_KEY`.**

### 9b — Language

- [x] `language` column on `profiles` (migration `20260801090000`)
- [x] `GET`/`PUT /v1/preferences`; the choice list ships in the response so the client hard-codes nothing
- [x] Deepgram gets a `language` parameter it never had
- [x] A line in the **cached** system block saying what to answer in
- [x] Practice mode included — questions, feedback and rewrites all follow the session language
- [x] Dropdown in Settings

Thirteen options, including Deepgram's `multi` for code-switching. Codes are an
**allow-list enum**, not free text: the value is interpolated into the Deepgram
query string, and `LanguageTest` asserts that `en&punctuate=false` and friends
are rejected.

The language belongs in the cached prefix — it is constant for a session, so it
costs one cold write and then caches, and two users in different languages
correctly get different prefixes rather than one answering in the other's.

**Exit criterion:** set Portuguese, speak Portuguese, transcript and answer both
come back in Portuguese.

**Wiring verified, the speaking half is manual.** The Deepgram URL, the prompt
line, and the allow-list are all under test. **Needs a real key and a human
speaking Portuguese to close.**

### 9c — Coding mode and Markdown rendering

- [x] Answers render through `react-markdown` with fenced, copyable code blocks
- [x] A screenshot switches to a coding system prompt — approach, code, complexity, edge cases
- [x] `coding-max-tokens: 2048`, because 1024 truncates a function halfway through
- [x] Copy buttons on code blocks and on the whole answer

**Coding mode is the screenshot path, not a toggle.** That is where a LeetCode
problem comes from, and a separate mode switch would be configurability nobody
asked for. Someone in a coding call where the problem is read aloud has the ask
bar.

> **Two prompts means two cached prefixes, and that is not a bug.** With the
> one-hour TTL both stay warm after first use, so alternating costs one cold
> write each and nothing after. A zero `cacheReadInputTokens` on the first
> screenshot of a session is that write.

**Copy goes through Electron's clipboard, not `navigator.clipboard`**, which
needs a secure context the packaged `file://` build does not have — exactly the
kind of thing that works in dev and fails once installed.

**Exit criterion:** screenshot a LeetCode problem, get a fenced, monospaced,
copyable code block with complexity analysis, not truncated.

**Request shape verified; the answer needs a key.** The 2048 ceiling is asserted
on the wire, and the two prompts are asserted not to share a prefix. **Whether
the code is correct needs `ANTHROPIC_API_KEY`.**

### 9d — Post-call notes

- [x] `session_summaries` table (migration `20260801100000`)
- [x] `GET /v1/sessions/{id}/summary` — generates on first call, then serves from storage
- [x] Shown in the History detail screen and included in the Markdown export, above the transcript
- [x] `409` rather than a billed call for a session with nothing in it

**Stored, unlike the practice report.** That route regenerates its themes on
every view, which this file already flags as re-billing a model call to read a
page; a recap is read far more often than it is produced. `key_points` and
`action_items` are `text[]` rather than `jsonb` because Spring Data JDBC maps
Postgres arrays with no custom converter, and nothing ever queries inside them.
Written with `JdbcAggregateTemplate.insert` rather than `save`: the id *is* the
session id and is therefore never null, so `save` would issue an UPDATE against
a row that does not exist.

**Exit criterion:** finish a session, open History, see a recap with key points
and action items; reopening it makes no second model call.

**Met.** `SessionSummaryStorageTest` runs it end to end against the real
database: three reads, one model call, lists intact through `text[]`.

### Cross-cutting

- [x] Auto-scroll on both panes, but only while the reader has not scrolled away
- [x] `README.md` and this file updated; version `0.9.0` in both `package.json`s and `pom.xml`

### Verification

**Closed after the merge.** The Windows reserved port range that was blocking
Supabase's `54322` is gone, so the database half finally ran. Note that
`supabase start` restores from a cached snapshot and does **not** replay new
migrations — `supabase db reset` is what applied these two.

- **Both migrations apply clean** — `20260801090000_user_language` and
  `20260801100000_session_summaries`, replayed from an empty database.
- **`PreferencesServiceTest`** (9 tests, real Postgres): a new user defaults to
  English, a language round-trips, the stored value is the code Deepgram
  expects, one user's language does not touch another's, an unknown or
  URL-tampering code is rejected without being stored, and an unreadable or
  retired value falls back to English rather than taking the session down.
- **`SessionSummaryStorageTest`** (8 tests, real Postgres): lists survive the
  `text[]` round trip, the model is called **once** across three reads, an empty
  session is refused without billing a call, another user gets a 404 and no model
  call either way, and deleting the session cascades the recap away.

**182 Java tests and 205 TypeScript tests, all green.**

Two things this run caught that nothing else would have:

- **`PracticeServiceTest` had a broken context.** Phase 9b gave `PracticeService`
  a `PreferencesService` dependency, and the `@DataJdbcTest` slice imports beans
  explicitly — so every test in that class errored on startup. Only a run with a
  database reaches that failure.
- **The recap's storage was rewritten.** `SessionSummaryStore` replaces the
  entity, repository and `JdbcAggregateTemplate` with explicit SQL — one class
  instead of three moving parts, with the insert race handled by
  `on conflict do nothing` rather than a caught `DuplicateKeyException`.

Still needing a real key, unchanged from Phases 3–6: whether follow-ups read as
follow-ups, whether the Portuguese is good, and whether the coding answers are
correct.

---

## Phase 10 — Hardening

**Goal:** finish the features that were already built. Nothing new ships here.
**Estimate:** 1 day · **Risk:** low · **Depends on:** all

No feature gap drove this one — three specific pieces of unfinished work did.
Version moves to `0.10.0`.

### 10a — The live session survives a dropped connection

- [x] The client sends the `ping` the protocol defined in Phase 3 and the server
      has answered since Phase 3. It was **never sent**: the schema, the Java
      handler and `SessionWebSocketHandlerTest::pingIsAnsweredWithPong` all
      existed around a client that had no heartbeat
- [x] Reconnect is unbounded with a 30s ceiling and ±20% jitter, replacing the
      give-up after five attempts (~15s)
- [x] `socket.onerror` no longer reports `'error'`. `onClose` is the one place
      that decides the next state; reporting from both made a retry that was
      still pending look terminal
- [x] `'error'` is now reserved for protocol drift — the one failure reconnecting
      cannot fix
- [x] Dropped audio frames are counted and shown, instead of a silent gap

A half-open socket — slept laptop, Wi-Fi handoff, VPN drop — is invisible to
`onclose`; the OS holds it open for minutes. 15s between pings and a 10s pong
deadline catches it in ~25s. The timeout closes the socket by hand and lets the
existing `onClose` path do the reconnecting, rather than opening a second one.

The reconnect notice is deliberately **not** dismissible: it is the live state of
the session, and hiding it would hide that nothing is being transcribed.

**Exit criterion:** kill the server mid-session; the overlay counts up
`reconnecting… (attempt N)` and never says it gave up. Start the server again and
the session returns to `ready` on its own, without touching Stop/Start.

**Met.** 11 new tests in `net/session.test.ts` (27 total), including regression
guards that `'error'` is never reported while a retry is pending and that a
`pong` is absorbed by the socket rather than reaching the UI. The two-machine
manual check is still worth doing before a release.

### 10b — Server failures and misconfiguration are legible

- [x] `ApiExceptionHandler`, a global `@RestControllerAdvice` returning RFC 9457
      `problem+json`. Extending `ResponseEntityExceptionHandler` also fixes the
      multipart limit surfacing as a bare 500
- [x] A `ResponseStatusException` handler in front of the catch-all — without it
      the catch-all matches it too and turns every deliberate 404 into a 500
- [x] The catch-all never echoes the exception message; it is logged instead
- [x] The five per-controller handlers return `ProblemDetail` for consistency
- [x] One `net/http.ts` helper replaces four copies of the same authed fetch and
      reads `detail`, so the overlay shows a sentence and not a JSON blob
- [x] `ProviderHealthIndicator`: a missing provider key reports DOWN on
      `/actuator/health` and warns at startup, instead of arriving as a vendor
      401 an hour later
- [x] `AnthropicAnswerEngine` fails with "the model is not configured on this
      server" rather than sending the literal key `"unset"`
- [x] Multipart limit raised to 10MB — Boot's 1MB default is smaller than an
      ordinary PDF résumé, so the Phase 5 upload failed on real files
- [x] `logging.level.ai.vader.server` defaults to `INFO`, `LOG_LEVEL` to override

`@Validated`/`@NotBlank` on the properties was considered and rejected: tests and
local development boot without keys on purpose, and `AnthropicTokenCounter`'s
estimate fallback exists for exactly that case. Health is the right signal —
`fly.toml` already probes it, so a deploy with missing secrets now fails there.

**Exit criterion:** an unhandled failure returns `problem+json` without repeating
what the exception said; a deliberate 404 stays a 404; `/actuator/health` is DOWN
with no `ANTHROPIC_API_KEY`.

**Met.** `ApiExceptionHandlerTest` asserts both over real HTTP;
`ProviderHealthIndicatorTest` covers both keys, each missing alone, both missing,
and a whitespace-only key.

### Cross-cutting

- [x] `README.md` and this file updated; version `0.10.0` in both `package.json`s
      and `pom.xml`
- [x] `.env.example` documents `LOG_LEVEL` and that the provider keys are
      required in a deployed environment

### Verification

**189 Java tests and 222 TypeScript tests, all green.** `pnpm typecheck` and
`pnpm lint` clean.

Two things worth recording:

- **`format:check` was already failing on `main`** across six files that predate
  this phase, so CI was red before any of this. Fixed here in its own commit
  rather than mixed into the changes above.
- **`MaxUploadSizeExceededException` did not need a handler.**
  `ResponseEntityExceptionHandler` already maps it; adding one broke context
  startup with an ambiguous-mapping error. Extending the base class was the whole
  fix — the 500 was only ever there because no advice existed.

Still needing a real key, unchanged: nothing in this phase touches prompt or
answer quality.

---

## Summary

| Phase | Deliverable                         | Language | Est.         | Risk        |
| ----- | ----------------------------------- | -------- | ------------ | ----------- |
| 0     | Monorepo scaffold (pnpm + Maven)    | both     | 1 d          | —           |
| 1     | Capture-invisible overlay + hotkeys | TS       | 1 d          | Low         |
| 2     | 2-channel audio pipeline            | TS       | 1–2 d        | **Highest** |
| 3     | Authenticated WS + live transcript  | Java     | 3 d          | Medium      |
| 4     | Streaming answers + screenshot Q&A  | both     | 2–3 d        | Medium      |
| 5     | Knowledge base                      | both     | 1–2 d        | Low         |
| 6     | Practice mode                       | both     | 1–2 d        | Low         |
| 7     | History and review                  | both     | 1 d          | Low         |
| 8     | Installer + deployed backend        | both     | 1–2 d        | Medium      |
| 9     | Ask bar · language · coding · notes | both     | 2–3 d        | Low         |
| 10    | Hardening (reconnect · server ops)  | both     | 1 d          | Low         |
|       | **Total**                           |          | **~15–20 d** |             |

**Minimum demoable product:** Phases 0–4. Phases 5–8 make it a product someone else can use.

The Java backend adds ~1–2 days versus a Node backend, concentrated in Phase 3 (Spring WebSocket wiring, security config, OkHttp Deepgram client). It buys a statically typed, well-instrumented service with first-class virtual-thread concurrency and an official Anthropic SDK.

---

## Deferred to post-MVP

Explicitly out of scope — listed so they are not re-debated mid-build:

- macOS support (`NSWindowSharingNone` is ignored by ScreenCaptureKit-based apps, so the capture-invisibility guarantee does not hold)
- Billing and subscriptions
- Offline / local model path (NVIDIA Parakeet, whisper.cpp, Ollama)
- Auto-update
- Team or multi-seat accounts
- Native GraalVM image for the backend (fast startup is irrelevant for a long-lived WebSocket server)
- Anti-proctoring evasion — permanently out of scope, not deferred
