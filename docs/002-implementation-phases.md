# 002 — MVP Implementation Phases

**Status:** approved — Phases 0–5 done (see each exit criterion for what is still a manual check), Phase 6 next
**Scope:** Windows-only MVP — working app, no billing
**Backend:** Java 21 + Spring Boot 3.4 (Maven)
**Total estimate:** ~12–16 days

Eight phases. Each has a hard exit criterion — do not start the next phase until the current one passes it. The ordering is driven by risk, not convenience: the two things most likely to fail (system-audio loopback, capture-invisible overlay) are proven in Phases 1 and 2, before anything is built on top of them.

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
```

**Parallelizable:** Phases 1, 2, and 3 are independent after Phase 0 — and now split cleanly by language, so a second person can take the Java track (Phase 3) while the first does the Electron track (Phases 1–2). Solo, run them in the order given; Phase 2 carries the most risk and must not be deferred. Phases 5, 6, and 7 are independent of each other.

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
- [x] GitHub config: CI workflow, PR template, issue templates, Dependabot

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
**Estimate:** 1–2 days · **Risk:** low · **Depends on:** Phase 4

Same STT and LLM pipeline, different orchestration. Cheap to build, and it is the version of the product you can show anyone.

### Tasks

- [ ] Generate a question set from the stored job description
- [ ] Present one question at a time; record the spoken answer via the mic channel only
- [ ] Grade each answer against the job description — structure, specificity, relevance
- [ ] Session report: per-question scores, concrete rewrites, weakest themes
- [ ] Persist practice sessions in history

### Exit criterion

Complete a 5-question mock interview and receive per-question feedback that identifies a real weakness in a deliberately vague answer.

---

## Phase 7 — Session history and review

**Goal:** nothing is lost when the call ends.
**Estimate:** 1 day · **Risk:** low · **Depends on:** Phase 4

### Tasks

- [ ] REST endpoints: list sessions, fetch one with turns, delete
- [ ] Session list with date, duration, question count
- [ ] Review screen: full transcript paired with the answers given
- [ ] Export a session to Markdown
- [ ] Delete cascades to turns

### Exit criterion

Close a session, reopen the app, and review the full transcript and answers.

---

## Phase 8 — Packaging and release

**Goal:** an installable `.exe` plus a deployed backend.
**Estimate:** 1–2 days · **Risk:** medium — first-run issues surface only here · **Depends on:** all

### Tasks

- [ ] `electron-builder` NSIS target: installer + portable variants
- [ ] App icon, product metadata, version scheme
- [ ] Backend: `mvnw spring-boot:build-image` (Buildpacks) or a multi-stage Dockerfile
- [ ] Deploy the container (Fly.io / Railway / Render); WSS with a real certificate
- [ ] Point the desktop build at the production backend URL via build-time env
- [ ] First-run flow: sign in → grant mic permission → knowledge-base prompt
- [ ] Crash and error reporting
- [ ] Windows build check with an actionable message on unsupported versions
- [ ] Smoke-test on a **clean** Windows VM with no dev tools installed

### Exit criterion

Install the `.exe` on a clean Windows VM, sign in, join a call, and get a working answer — with no developer tooling present on that machine.

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
|       | **Total**                           |          | **~12–16 d** |             |

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
