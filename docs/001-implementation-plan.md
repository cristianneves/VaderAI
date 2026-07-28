# 001 — Architecture and Technical Plan (MVP)

**Status:** approved, not started
**Scope:** Windows-only MVP — working app, no billing
**Stack:** Electron + React desktop · **Java 21 + Spring Boot backend** · Supabase · Deepgram · Claude Opus 5

This document holds the architecture, latency budget, cost model, and risk register. For the phase-by-phase build checklist, see [`002-implementation-phases.md`](002-implementation-phases.md).

---

## Target latency budget

This is the product. Every decision below is designed around it.

| Stage                                     | Budget         |
| ----------------------------------------- | -------------- |
| Audio frame → Deepgram interim result     | ~150 ms        |
| End-of-question detection (silence gate)  | 700 ms         |
| Claude time-to-first-token (effort `low`) | 400–700 ms     |
| **Question ends → first visible token**   | **~1.3–1.6 s** |

For reference: ParakeetAI lands in 2–4 s. Cluely's advertised ~300 ms is measured from transcript delta, not from end of speech — do not chase it.

---

## Architecture

```
┌─ Electron desktop app (Windows) ───────────┐
│  main:     hotkeys, overlay window,        │
│            setContentProtection,           │
│            desktopCapturer screenshots     │
│  renderer: React overlay UI                │
│            AudioWorklet → 16 kHz PCM16     │
└──────────────┬─────────────────────────────┘
               │  WebSocket
               │  ↑ binary audio frames + control JSON
               │  ↓ transcript deltas + answer tokens
┌──────────────▼─────────────────────────────┐
│  Spring Boot 3.4 · Java 21 · Maven         │
│   ├─ SessionWebSocketHandler (binary+text) │
│   ├─ DeepgramSttProvider (OkHttp WS)       │
│   ├─ AnthropicAnswerEngine (anthropic-java)│
│   ├─ TurnDetector · PromptAssembler        │
│   └─ Spring Security — Supabase JWT        │
│      virtual threads enabled               │
└──────────────┬─────────────────────────────┘
               │
┌──────────────▼─────────────────────────────┐
│  Supabase — Auth (JWT) + Postgres          │
│  profiles, sessions, transcript_turns,     │
│  knowledge_docs                            │
└────────────────────────────────────────────┘
```

---

## Backend stack decisions

### Java 21 + Spring Boot 3.4, Maven, Spring MVC (not WebFlux)

**Java 21**, not 23: `JAVA_HOME` on this machine points at JDK 21, it is the current LTS, and Spring Boot 3.4 targets it cleanly. Note that `java` on `PATH` here is 23 while Maven follows `JAVA_HOME` — pin the toolchain in `pom.xml` so the two never diverge silently.

**Maven**, not Gradle: Maven 3.9.9 is already installed and Gradle is not. Commit the Maven Wrapper (`mvnw`) so CI and other machines need no local Maven.

**Spring MVC + `spring-boot-starter-websocket`, with virtual threads** (`spring.threads.virtual.enabled=true`) — _not_ WebFlux. The Anthropic Java SDK's streaming API is a blocking `StreamResponse<RawMessageStreamEvent>`, so a reactive stack would spend the whole project bridging blocking calls onto schedulers. Virtual threads give the same I/O concurrency (one carrier thread serves thousands of parked sessions) with straight-line blocking code. This workload is pure I/O fan-out — client WS ↔ Deepgram WS ↔ Anthropic SSE — which is exactly what virtual threads are for.

### Deepgram over OkHttp's WebSocket client

`anthropic-java` already brings OkHttp in via `AnthropicOkHttpClient`, so the WebSocket client is on the classpath for free. Avoids taking a dependency on a third-party Deepgram SDK whose maintenance status we would have to track.

### Supabase owns the schema; Java does not migrate

The Supabase CLI owns SQL migrations and RLS policies. **Flyway and Liquibase are disabled** — two migration owners on one database is a guaranteed conflict. Spring uses **Spring Data JDBC** (lighter than JPA; we have no object graph to speak of).

> **Consequence that must not be forgotten:** the backend connects with the service role, which **bypasses RLS**. Every query must be scoped to the `userId` extracted from the verified JWT, in the service layer. RLS protects the _client-direct_ path, not ours. Enforce with a repository-level convention and a test that proves cross-user reads fail.

---

## Three backend traps to design around

These are Spring/WebSocket specifics that are cheap to handle up front and expensive to discover in Phase 4.

**1. WebSocket handshakes cannot carry an `Authorization` header.**
The browser/Electron-renderer `WebSocket` API provides no way to set request headers. Three options: token in the query string (leaks into access logs), the `Sec-WebSocket-Protocol` subprotocol hack, or **first-frame authentication**. We use first-frame auth: the client connects, sends `hello` with the Supabase access token as its first message, and the server closes with `1008` if no valid token arrives within 5 seconds. No token in URLs, no header workaround.

**2. Default binary buffer is 8 KB — our frames are 6.4 KB.**
One 100 ms frame at 16 kHz, 2 channels, PCM16 = `16000 × 0.1 × 2 × 2` = **6400 bytes**. That fits under Spring's 8192-byte default, but with almost no headroom — a 150 ms frame would silently break the connection. Raise both buffers to 64 KB via `ServletServerContainerFactoryBean`.

**3. `WebSocketSession` is not safe for concurrent sends.**
Two async producers write to one session: Deepgram transcript deltas and Anthropic answer tokens. Concurrent `sendMessage` calls corrupt the frame stream. Wrap every session in `ConcurrentWebSocketSessionDecorator` at registration time.

---

## Repository layout

Polyglot monorepo — pnpm workspace for the TypeScript side, Maven for the backend:

```
VaderAI/
├── package.json                    # pnpm workspace root (desktop + protocol only)
├── pnpm-workspace.yaml
├── docs/
├── contracts/
│   └── messages/*.json             # shared WS fixtures; both sides test against these
├── packages/
│   └── protocol/src/index.ts       # zod schemas (TypeScript side of the contract)
├── apps/
│   ├── desktop/                    # Electron — pnpm workspace member
│   │   └── src/
│   │       ├── main/index.ts       # BrowserWindow + content protection
│   │       ├── main/hotkeys.ts
│   │       ├── main/capture.ts     # desktopCapturer screenshots
│   │       ├── preload/index.ts
│   │       └── renderer/
│   │           ├── App.tsx
│   │           ├── audio/capture.ts        # loopback + mic
│   │           ├── audio/pcm-worklet.js    # resample → PCM16
│   │           └── net/socket.ts
│   └── server/                     # Spring Boot — Maven project
│       ├── pom.xml
│       ├── mvnw / mvnw.cmd
│       └── src/main/
│           ├── java/ai/vader/server/
│           │   ├── VaderAiApplication.java
│           │   ├── config/         # WebSocketConfig, SecurityConfig
│           │   ├── session/        # SessionWebSocketHandler, SessionRegistry
│           │   ├── stt/            # SttProvider, DeepgramSttProvider
│           │   ├── llm/            # AnswerEngine, AnthropicAnswerEngine
│           │   ├── turn/           # TurnDetector
│           │   ├── prompt/         # PromptAssembler
│           │   ├── knowledge/      # KnowledgeService
│           │   └── protocol/       # records mirroring the zod schemas
│           └── resources/application.yml
└── supabase/migrations/
```

### The protocol contract across two languages

The message set is small (~10 types) and mostly server→client, so hand-writing both sides is cheaper than a code generator. To stop the two from drifting:

- `packages/protocol` holds the zod schemas — the **source of truth**.
- `apps/server/.../protocol/` holds Java records with matching Jackson field names.
- `contracts/messages/*.json` holds one fixture per message type. The TypeScript side asserts each fixture parses under zod; the Java side asserts each fixture deserializes into the corresponding record. A field rename on one side fails the other side's test.

---

## Answer engine — Anthropic Java SDK

```xml
<dependency>
  <groupId>com.anthropic</groupId>
  <artifactId>anthropic-java</artifactId>
  <version>2.34.0</version>
</dependency>
```

```java
AnthropicClient client = AnthropicOkHttpClient.fromEnv();   // reads ANTHROPIC_API_KEY

MessageCreateParams params = MessageCreateParams.builder()
    .model("claude-opus-5")                    // String overload — no typed constant yet
    .maxTokens(1024L)
    .outputConfig(OutputConfig.builder()
        .effort(OutputConfig.Effort.LOW)       // the latency lever
        .build())
    .systemOfTextBlockParams(List.of(
        TextBlockParam.builder()
            .text(SYSTEM_PROMPT)
            .build(),
        TextBlockParam.builder()
            .text(knowledgeBase)               // résumé, job description, notes
            .cacheControl(CacheControlEphemeral.builder()
                .ttl(CacheControlEphemeral.Ttl.TTL_1H)
                .build())
            .build()))
    .addUserMessage(turnContext)
    .build();

try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
    stream.stream()
        .flatMap(event -> event.contentBlockDelta().stream())
        .flatMap(delta -> delta.delta().text().stream())
        .forEach(textDelta -> session.emitAnswerDelta(textDelta.text()));
}
```

Four things here are deliberate, and each is a trap if you get it wrong:

- **`Effort.LOW` is the latency lever — not disabling thinking.** Thinking is on by default on Opus 5 (omitting `.thinking(...)` runs adaptive), and turning it off has two documented failure modes: `<thinking>` tags leaking into visible output, and tool calls emitted as plain text where the call silently never runs. Low effort is faster _and_ safer. `ThinkingConfigDisabled` is additionally a 400 at `xhigh`/`max` effort.
- **Prompt caching is a prefix match.** Stable content goes before the breakpoint, volatile turn context after it. Any byte change before the breakpoint invalidates everything after — no timestamps, no session IDs in the cached block. Assert `usage.cacheReadInputTokens()` is non-zero from the second answer onward.
- **Fast mode is a beta path**, priced at $10/$50 per MTok vs. standard $5/$25. In Java it needs `client.beta().messages()`, `.addBeta(AnthropicBeta.FAST_MODE_2026_02_01)`, and `.speed(MessageCreateParams.Speed.FAST)` from the **beta** params package. Keep it behind a config flag, **off by default**, until standard-mode TTFT is measured on real calls.
- **`maxTokens(1024L)`** — answers are read while someone is waiting. Cap them.

> **When implementing:** verify exact builder and type names against the [anthropic-java](https://github.com/anthropics/anthropic-sdk-java) repo before writing code — particularly the image content block for the screenshot path, which is not shown above. Do not guess Java SDK bindings from the shape of the REST API.

---

## Screenshot path

`desktopCapturer.getSources({ thumbnailSize: { width: 1920, height: 1080 } })` → PNG → base64 → sent over the WebSocket → attached as an image content block **before** the text block.

Downsample to 1080p: Opus 5 accepts up to 2576 px on the long edge, but full-resolution images cost up to ~4784 tokens each. 1080p is the accuracy/cost sweet spot, and coordinates still map 1:1 to pixels, so no scale-factor math.

---

## Cost model

Per ~45-minute session, ~30 answers. Provider costs are unaffected by the backend language.

| Item                                                | Standard Opus 5            | Fast mode                 |
| --------------------------------------------------- | -------------------------- | ------------------------- |
| Claude (≈3k in / 400 out per answer, cached prefix) | ~$0.025/answer → **$0.75** | ~$0.05/answer → **$1.50** |
| Deepgram Nova-3 streaming, 2 channels               | ~$0.50–0.90                | same                      |
| **Total per session**                               | **~$1.25–1.65**            | **~$2.00–2.40**           |

Anthropic figures come from the current model pricing table ($5/$25 per MTok standard, $10/$50 fast) and are accurate. **The Deepgram rate is from memory and unverified** — check their pricing page before building a business model on it.

Prompt caching does real work here: cache reads bill at ~0.1x, so a 2k-token résumé costs ~$0.001 per answer instead of ~$0.01.

---

## End-to-end verification (run after Phase 4)

1. `mvnw spring-boot:run` starts the backend on :8787; `pnpm dev:desktop` opens the overlay.
2. Sign in; overlay shows _Connected_.
3. Start a Google Meet call from a second device; share your entire screen.
4. On the second device, confirm the overlay is **not** in the shared view.
5. The second device asks: _"Tell me about a time you handled a production incident."_
6. Transcript pane shows the question attributed to **Interviewer** within ~300 ms.
7. Answer begins streaming in under ~1.6 s and references your uploaded résumé.
8. Open a LeetCode problem, press `Ctrl+H` — an approach + complexity analysis appears.
9. Backend logs show a non-zero `cacheReadInputTokens` from the second answer onward.
10. `Ctrl+\` hides and restores the overlay without dropping the WebSocket.

---

## Known risks

| Risk                                                                       | Mitigation                                                                                                                              |
| -------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Loopback capture behaves differently across Electron versions              | Pin Electron ≥39 exactly; run Phase 2 before anything depends on it                                                                     |
| Windows < 10 build 2004 → overlay renders black in captures, not invisible | Detect build at startup, warn in settings                                                                                               |
| Blocking Anthropic stream starves a platform thread                        | Virtual threads on (`spring.threads.virtual.enabled=true`); verify with a load test that parked sessions do not consume carrier threads |
| Concurrent sends corrupt the WebSocket frame stream                        | `ConcurrentWebSocketSessionDecorator` on every session, from Phase 3                                                                    |
| Protocol drift between zod and Java records                                | Shared JSON fixtures in `contracts/`, asserted by both test suites                                                                      |
| Service-role connection bypasses RLS                                       | Every query scoped by JWT `userId` in the service layer; cross-user read test                                                           |
| Auto-trigger fires on backchannel ("mm-hmm", "right")                      | 700 ms endpointing + 2 s debounce; add a Haiku 4.5 is-this-a-question classifier if noise persists                                      |
| STT vendor lock-in                                                         | `SttProvider` interface from day one; local Parakeet / whisper.cpp as the offline fallback                                              |
| Per-session cost higher than expected                                      | Fast mode behind a flag; measure standard-mode TTFT before paying 2x                                                                    |

---

## Open items

Decide with measurements during Phase 4, not from first principles:

- Fast mode on by default, or only when measured standard TTFT exceeds ~1 s?
- Auto-ask on by default, or manual-trigger-only until the turn detector is tuned on real calls?
