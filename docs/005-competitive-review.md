# 005 — Competitive review: where we stand at 0.11.0

**Status:** review complete — the resulting work is Phase 12 in [`002-implementation-phases.md`](002-implementation-phases.md)
**Date:** 31 July 2026
**Compared against:** our tree at `0.11.0`, all eleven phases built
**Supersedes:** [`004-competitive-analysis.md`](004-competitive-analysis.md), which was written against `0.8.0` and drove Phase 9

[`004`](004-competitive-analysis.md) asked where we stood after eight phases. Its
answer — _"we win on the things that are hard to build and lose on the things
that are cheap to build"_ — was right, and Phases 9 through 11 spent themselves
buying back the cheap things. This document asks the question again, one year of
competitor releases and three phases later.

The headline is not a feature gap. **Reading our own code against Cluely's
coding story turned up a defect: `Ctrl+H` almost certainly never worked on a real
screen.** That is covered in [§ Defects](#defects-found-by-reading-the-code) and
is most of what Phase 12 is.

---

## What changed on our side since 0.8.0

Seven of `004`'s thirteen ranked gaps are closed. The gap table in that document
should not be read as current — these rows have flipped:

| `004` gap                           | Then | Now                                                               |
| ----------------------------------- | ---- | ----------------------------------------------------------------- |
| 1 · No text input anywhere          | ❌   | ✅ ask bar, `Ctrl+K`, 2000-char ceiling                           |
| 2 · No conversational memory        | ❌   | ✅ 3 exchanges replayed as real turns, after the cache breakpoint |
| 3 · English only                    | ❌   | ✅ 13 languages plus Deepgram `multi` code-switching              |
| 4 · Plain-text answers              | ❌   | ✅ Markdown, fenced code, `coding-max-tokens: 2048`               |
| 5 · No post-call notes              | ❌   | ✅ `session_summaries`, generated once and stored                 |
| 6 · No auto-scroll                  | ❌   | ✅ both panes, `ui/auto-scroll.ts`                                |
| 7 · `error` frames silently dropped | ❌   | ✅ severity-mapped, `net/problem.ts`                              |
| — Quick actions                     | ❌   | ✅ `answer/quick-actions.ts` (four, now seven — see 12b)          |

Phases 10 and 11 also added a heartbeat, unbounded reconnect with jitter, RFC
9457 `problem+json` on every REST failure, and a token provider that refreshes on
each handshake rather than replaying the one captured at connect.

---

## Cluely's 2026 feature set

Refreshed from [docs.cluely.com](https://docs.cluely.com/feature/liveinsights)
and current third-party reviews. New since `004` recorded it:

- **Dynamic Actions** — the Live Insights card surfaces _"real-time questions,
  keywords, and suggestions detected from the transcript"_, ranked, with **Tab**
  to pull an answer to the top-ranked one. This is the one genuinely new
  interaction, and it is the interesting gap (see below).
- **Smart Mode** — a lightning-bolt toggle "for coding assistance". A mode you
  switch on, not one inferred from a screenshot.
- **Stealth Chat** — `CMD/CTRL+Shift+Enter` for answers during invisibility mode.
- Session timer, mid-session audio toggle, transcript/insights view switch, a
  `CMD/CTRL+\` hide keybind, and a "Customize Cluely" dropdown that swaps prompt
  sets (e.g. sales-focused) — the productised form of playbooks.

### On the pricing

`004` states $149.99/mo for the undetectability tier as fact. **Sources now
disagree** and the number should not be quoted without a caveat: current reviews
variously report Starter free (5 responses/day), Pro ~$20/mo, Pro + Undetectability
at **$75/mo in some sources and $149.99 in others**, Enterprise ~$200/mo, and a
separate $8/wk mobile plan. Reviews also describe the stealth overlay as
rendering "through low-level GPU hooks that bypass standard screen-capture APIs",
which is a stronger claim than the documented Windows flag we use.

What does not change: the invisible overlay is a **paid tier** for them and one
line — `win.setContentProtection(true)` — for us. That remains the most
interesting fact in the comparison and the strongest thing to say when this
product is priced.

---

## Defects found by reading the code

These are not gaps against a competitor. They are things that claim to work.

### 1. `Ctrl+H` closed the live session instead of answering — **fixed in 12a**

`main/screenshot.ts` captured 1920×1080 and base64-encoded PNG; a real screen
encodes to 270–670 KB. `WebSocketConfig` capped text frames at 64 KiB and
`SessionWebSocketHandler` left `supportsPartialMessages()` at its `false`
default, so Tomcat closed the connection with **1009 before the handler ever
ran** — no error frame, no answer, session gone. Only a near-blank screen would
have fitted.

This is the worst kind of defect: the feature demos fine on a stub, the failure
looks like a network drop, and it sits on the exact path — coding interviews —
where Cluely's Smart Mode and ParakeetAI's headline feature compete.

### 2. Coding mode was unreachable except through a screenshot — **fixed in 12b**

`CODING_SYSTEM_PROMPT` and `coding-max-tokens: 2048` shipped in Phase 9c, but
`PromptAssembler` selected coding **only** when an image was attached. A problem
read out loud, or pasted into the ask bar, got the first-person interview prompt
("a few sentences someone can say out loud") at half the token budget. Cluely
ships this as a toggle; we had the prompt and no way to ask for it.

### 3. No spend guard anywhere — **fixed in 12c**

The live `ask` path, `/v1/practice/*` and `/v1/sessions/{id}/summary` each
reached Opus with no per-user cap. Auto-ask compounds it: it fires on **any**
interviewer utterance, so "can you hear me?" costs a full call. A client stuck in
a reconnect loop was an unbounded bill.

### 4. Time-to-first-token was never measured — **fixed in 12d**

`docs/001` budgets ~1.3–1.6 s and treats it as the product's central claim.
`docs/001:254` explicitly parked two decisions — fast mode on by default, and
auto-ask on by default — to be settled _"with measurements during Phase 4, not
from first principles."_ Those measurements were never taken, because nothing
recorded a time. Both defaults are still guesses.

### 5. The isolation guarantee is not verified by CI — **open**

`UserScopingTest` and `SessionSummaryStorageTest` `assumeTrue` themselves into a
skip when local Supabase is unreachable. That is exactly the CI condition, so the
cross-user scoping proof — the thing standing in for RLS, since the backend uses
the service role — runs only on a developer machine that happens to have the
stack up. Not fixed here; it needs a Postgres service container in the workflow.

---

## Ranked remaining gaps

Impact ÷ cost. `004`'s unscheduled 8–13 carried forward and renumbered.

|  #  | Gap                                                       | Why it matters                                                                                                                               | Cost | Phase |
| :-: | --------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | :--: | :---: |
|  1  | **Auto-ask fires on any interviewer utterance**           | No question classification. Greetings and "can you hear me?" each buy a full Opus call. 12c bounds the bill but does not fix the noise.      |  M   |  13   |
|  2  | **No Dynamic Actions / suggestion ranking**               | Cluely's one genuinely new interaction: ranked suggestions off the transcript, Tab to answer the top one. Shares a classifier with gap 1.    |  L   |  13   |
|  3  | **Overlay is a fixed 460×620 box**                        | No resize, no opacity, no click-through, no position memory. It permanently covers a rectangle of the meeting and reopens at the OS default. |  M   |  13   |
|  4  | **No device picker, mute, or level meter**                | "Which mic am I using?" is answerable only by dumping a WAV and opening Audacity. Cluely has a mid-session audio toggle.                     |  S   |  13   |
|  5  | **No tone / verbosity / persona control**                 | Cluely sells "Customize Cluely" prompt sets. We have one hard-coded voice.                                                                   |  S   |  13   |
|  6  | **CI does not prove cross-user isolation**                | Defect 5 above. The one test guarding the thing that replaces RLS does not run where it matters.                                             |  S   |  13   |
|  7  | **No sign-out, tray icon, auto-update, or usage display** | Ordinary hygiene, all absent. There is now an hourly cap with no way for a user to see where they are against it.                            |  M   |  13   |
|  8  | Fixed 3-slot knowledge base                               | Competitors allow dozens. Our 8,000-token ceiling is still display-only and enforces nothing.                                                |  M   |   —   |
|  9  | No retrieval — whole KB on every question                 | Fine at 3 slots. Becomes the reason gap 8 is hard.                                                                                           |  L   |   —   |

Gaps 1 and 2 are one piece of work: both need a question classifier, and 12c
deliberately did not build one — every cheap heuristic is wrong in the direction
that matters (a length gate drops "Why Postgres?" at 14 characters and keeps
"Can you hear me?" at 16), and a classifier call is itself a model call. The
`ttftMs` numbers from 12d are the input that makes that decision measurable
rather than argued.

---

## Where we are still ahead

Unchanged from `004`, and all four are structural rather than cosmetic.

1. **Speaker attribution is a wire fact, not a model inference.** Channel 0 is
   system audio, channel 1 is the mic, `multichannel=true` to Deepgram. Everyone
   else diarises a mixed stream, which degrades exactly when it matters —
   cross-talk, two interviewers, a bad line.
2. **The latency budget is documented — and now measured.** 12d closes the one
   thing `004` could not claim: that we know the number rather than believe it.
3. **Practice mode.** Neither competitor has one. Still the only feature we have
   that is demonstrable to someone who would never use a live copilot.
4. **Cost discipline.** A 1-hour cached prefix with volatile context strictly
   after the breakpoint. 12a strengthened this sideways: halving screenshot
   resolution cut ~1,536 input tokens per screenshot ask, and those tokens sit
   after the breakpoint where they are re-billed every time.

**Anti-proctoring evasion stays permanently out of scope.** `004`'s analysis of
why holds without amendment, and reviews now describing Cluely's overlay as
GPU-hook-based reinforce it: that is a different category of software, and it is
the category antivirus vendors write signatures for.

---

## Sources

Competitor claims are from vendor documentation and third-party reviews, not from
testing the products. Pricing in particular is reported inconsistently across
sources and is quoted here as a range for that reason.

- [docs.cluely.com — Live Insights](https://docs.cluely.com/feature/liveinsights) — Ask AI bar, Dynamic Actions, Smart Mode, Stealth Chat, keybinds
- [Cluely AI Pricing 2026 — FinalRound](https://www.finalroundai.com/blog/cluely-pricing)
- [Cluely Review in 2026 — FinalRound](https://www.finalroundai.com/blog/cluely-review-pros-cons)
- [Cluely Review 2026 — Interview Sidekick](https://interviewsidekick.com/blog/cluely-review)
- [Cluely Review — tldv](https://tldv.io/blog/cluely-review/)
- [`004-competitive-analysis.md`](004-competitive-analysis.md) — the ParakeetAI comparison, still current
