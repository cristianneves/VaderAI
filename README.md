# VaderAI

A real-time AI copilot for interviews and study sessions. VaderAI runs as an always-on-top desktop overlay on Windows that listens to your call, watches your screen, and streams answers only you can see.

**Status:** pre-implementation. Architecture and stack are decided; no application code has been written yet.

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
└── supabase/           # Migrations (Supabase CLI owns the schema)
```

`apps/`, `packages/`, `contracts/`, and `supabase/` do not exist yet — they are created in Phase 0.

---

## Getting started

Not yet runnable. Start at **Phase 0 — Foundations** in [`docs/002-implementation-phases.md`](docs/002-implementation-phases.md).

**Local toolchain:**

- JDK **21** (`JAVA_HOME` must point at it — Maven follows `JAVA_HOME`, not `PATH`)
- Maven 3.9+ (or just use the committed `mvnw` wrapper)
- Node 22+ and pnpm 10+ (via `corepack`)

**Accounts needed before Phase 3:**

- Supabase project (auth + Postgres)
- Deepgram API key
- Anthropic API key

```bash
pnpm install          # TypeScript side
pnpm dev:server       # Spring Boot on :8787
pnpm dev:desktop      # Electron overlay
pnpm dev              # both
```

---

## Documentation

| Path                                                                     | Contents                                                |
| ------------------------------------------------------------------------ | ------------------------------------------------------- |
| [`docs/001-implementation-plan.md`](docs/001-implementation-plan.md)     | Architecture, latency budget, cost model, risk register |
| [`docs/002-implementation-phases.md`](docs/002-implementation-phases.md) | Phase-by-phase build checklist — **start here**         |

---

## Scope boundary

VaderAI implements the assistant and uses Electron's documented `setContentProtection` API so the overlay is excluded from screen shares — the same OS call password managers and banking apps use.

It does **not** implement anti-proctoring evasion: defeating proctoring software, process-name spoofing, or kernel-level hooks are out of scope and will not be added.
