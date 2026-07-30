# 004 — Competitive analysis: Cluely and ParakeetAI

**Status:** research complete — the resulting work is Phase 9 in [`002-implementation-phases.md`](002-implementation-phases.md)
**Date:** 30 July 2026
**Compared against:** our tree at `0.8.0`, all eight MVP phases built

This document exists to answer one question: after eight phases, where does
VaderAI actually stand against the two products people already pay for?

The short version — **we win on the things that are hard to build and lose on
the things that are cheap to build.** Two-channel speaker attribution, a real
latency budget, and graded practice mode are architecture; they took the whole
project and neither competitor has all three. A text box, a language dropdown,
and Markdown rendering are an afternoon each, and not having them is why a
side-by-side demo would go badly.

---

## The three products

| | **VaderAI** | **Cluely** | **ParakeetAI** |
| --- | --- | --- | --- |
| Positioning | Interview copilot | Live meeting copilot (sales, interviews, any call) | Interview copilot |
| Platforms | Windows | Windows, macOS, iOS | macOS, Windows, browser, mobile browser |
| Model | Claude Opus 5 | undisclosed | GPT‑5, GPT‑4.1, Claude Sonnet 4 (user-selectable) |
| Pricing | none (no billing built) | Free / $19.99 mo Pro / **$149.99 mo** for the undetectable overlay / $8 wk mobile | credits (0.5 per 30 min) + monthly/yearly unlimited; 10 × 10 min free |

That $149.99 line is the single most interesting number in this table and is
covered in [§ What we give away free](#what-we-give-away-that-cluely-charges-15000mo-for).

---

## Feature comparison

Legend: ✅ shipped · ⚠️ partial · ❌ absent

### Capture and transcription

| | VaderAI | Cluely | ParakeetAI |
| --- | :-: | :-: | :-: |
| System-audio capture (no meeting bot) | ✅ | ✅ | ✅ |
| Mic capture | ✅ | ✅ | ✅ |
| **Speaker attribution without diarization** | ✅ hardware, 2-channel | ❌ | ❌ |
| Live interim transcript | ✅ | ✅ | ✅ |
| Multi-language | ❌ **English only** | ✅ 12+ | ✅ 50+ (one per session) |
| Auto-detect meeting start | ❌ manual Start | ✅ | ✅ (paid tiers) |
| Input device picker | ❌ | ✅ | ✅ |

### Answers

| | VaderAI | Cluely | ParakeetAI |
| --- | :-: | :-: | :-: |
| Auto-answer on detected question | ✅ | ✅ | ✅ |
| Manual trigger | ✅ hotkey only | ✅ | ✅ |
| **Type a question / redirect the AI** | ❌ **no text input anywhere** | ✅ Ask AI chat bar | ✅ in-call messaging |
| **Follow-up / conversational memory** | ❌ every ask is stateless | ✅ | ⚠️ |
| Quick actions (recap, what to say next, fact-check) | ❌ | ✅ | ❌ |
| Screenshot / screen context | ✅ `Ctrl+H`, 1080p | ✅ OCR | ✅ |
| **Markdown + code blocks in output** | ❌ **plain text** | ✅ | ✅ |
| Coding-interview mode | ❌ one generic prompt | ⚠️ | ✅ headline feature |
| Answer length / tone / persona control | ❌ | ✅ playbooks | ⚠️ extra-context field |
| Regenerate / shorter / longer | ❌ | ✅ | ❌ |
| Model choice | ❌ | ❌ | ✅ |

### Grounding and knowledge

| | VaderAI | Cluely | ParakeetAI |
| --- | :-: | :-: | :-: |
| Résumé upload | ✅ PDF/DOCX/TXT/MD, server-side extraction | ✅ | ✅ 30 / 30 days |
| Arbitrary documents | ❌ fixed 3 slots | ✅ unlimited (Pro) | ✅ 100 / 30 days |
| Free-form extra instructions | ⚠️ the `notes` slot | ✅ custom prompts | ✅ |
| Prompt caching of the grounding block | ✅ 1 h breakpoint | unknown | unknown |
| Retrieval / relevance selection | ❌ whole KB every question | unknown | unknown |

### After the call

| | VaderAI | Cluely | ParakeetAI |
| --- | :-: | :-: | :-: |
| Session history | ✅ | ✅ | ⚠️ |
| Full transcript + answers, interleaved | ✅ | ✅ | ⚠️ |
| **Notes / recap / action items** | ❌ | ✅ shareable | ✅ auto |
| Follow-up email drafting | ❌ | ✅ | ❌ |
| Export | ✅ Markdown | ✅ | ⚠️ |
| **Mock interview with graded feedback** | ✅ 5 questions, 3-axis rubric, rewrites, themes | ❌ | ❌ |

### Overlay and stealth

| | VaderAI | Cluely | ParakeetAI |
| --- | :-: | :-: | :-: |
| Invisible to screen share | ✅ `WDA_EXCLUDEFROMCAPTURE`, free | ✅ **$149.99/mo tier** | ✅ |
| Never joins as a bot | ✅ | ✅ | ✅ |
| Always-on-top over full-screen apps | ✅ | ✅ | ✅ |
| Does not steal focus | ✅ `focusable: false` | ✅ | ✅ |
| Movable | ⚠️ 40 px per keypress / header drag | ✅ | ✅ |
| Resizable / opacity / position memory | ❌ fixed 460×620 | ✅ | ⚠️ |
| **Auto-scroll during a live call** | ❌ | ✅ | ✅ |
| Copy an answer | ❌ | ✅ | ✅ |
| Hidden from Task Manager / proctoring | ❌ **deliberate non-goal** | ⚠️ paid tier | ✅ advertised |

---

## Where we are genuinely ahead

Four things, and they are all structural rather than cosmetic — which means a
competitor cannot close them in a sprint.

**1. Speaker attribution is exact, and it is free.** Channel 0 is system audio,
channel 1 is the microphone, merged in a `ChannelMergerNode` and sent to Deepgram
with `multichannel=true`. Attribution is a wire fact, not a model inference.
Everyone else runs diarization on a mixed stream, which is probabilistic and
degrades exactly when it matters — cross-talk, two interviewers, a bad line. This
was the highest-risk decision in the project (Phase 2) and it paid.

**2. The latency budget is real and it is documented.** ~1.3–1.6 s from end of
question to first token: 150 ms to a Deepgram interim, a 700 ms silence gate, and
`Effort.LOW` on Opus 5 for time-to-first-token. Reviewers measure ParakeetAI at
**2–5 s** and note it publishes no benchmark. Cluely's advertised 300 ms is
measured from transcript delta rather than end of speech, which is not the same
quantity — [`001`](001-implementation-plan.md) already says not to chase it.

**3. Practice mode.** Neither competitor has one. ParakeetAI reviews say it
outright: *"no question bank, no mock interview mode, no structured prep — it's
real-time only. Nothing exists between interviews."* We generate a question set
from the stored job description, grade each spoken answer on structure /
specificity / relevance, rewrite it in the user's own voice, and name the themes
that cost them most across the session. This is the only feature we have that
neither competitor sells, and it is also the only part of the product that is
demonstrable to someone who would never use a live copilot.

**4. Cost discipline.** A 1-hour cached prefix on the system prompt plus
knowledge base, with volatile turn context strictly after the breakpoint. Cache
reads bill at ~0.1×, so a 2k-token résumé costs ~$0.001 per answer instead of
~$0.01. Neither competitor documents anything comparable.

### What we give away that Cluely charges $149.99/mo for

Cluely's Free and $19.99 Pro tiers do **not** include the invisible overlay. The
capture-invisible window — the entire reason the category exists — sits behind a
$149.99/month "Pro + Undetectability" plan.

Ours is one line: `win.setContentProtection(true)`, which maps to
`WDA_EXCLUDEFROMCAPTURE` on Windows 10 build 2004 and later. It works, it is
free, and it is verified by the header pill in the overlay.

This is a positioning fact, not an engineering one, and it is worth saying out
loud when the time comes to price this product.

---

## Ranked gaps

Ordered by impact ÷ cost. The first four are Phase 9.

| # | Gap | Why it matters | Cost | Phase 9 |
| :-: | --- | --- | :-: | :-: |
| 1 | **No text input anywhere in the app** | Cluely's core interaction is a chat bar; Parakeet has in-call messaging. We have two global hotkeys and no way to type a word. The `screenshot.note` field already exists in the protocol and is parsed then discarded. | S | 9a |
| 2 | **No conversational memory** | Every ask is a stateless single-turn call carrying only the raw transcript tail. The model cannot see its own prior answers, so it repeats itself and *"elaborate on that"* is structurally impossible. | M | 9a |
| 3 | **English only** | The Deepgram query string is hard-coded with no `language` param and there is no prompt-side language instruction. Parakeet: 50+. Cluely: 12+. This excludes an entire market for roughly a day of work. | S | 9b |
| 4 | **Plain-text answers** | We pitch `Ctrl+H` for LeetCode and then render `<p>{answer.text}</p>` with `max_tokens: 1024`. Coding interviews are Parakeet's headline feature; unformatted, truncated code is the most visible weakness in a demo. | S | 9c |
| 5 | **No post-call notes or recap** | Both competitors ship it. We store the whole transcript and produce no summary. Also the cheapest way to widen the product past interviews. | M | 9d |
| 6 | **No auto-scroll** | The most-felt gap in an actual live interview: the transcript scrolls out of view and the user must reach for the mouse wheel mid-answer. | XS | 9 |
| 7 | **Server `error` frames are silently dropped** | `stt_failed` / `llm_failed` / `unauthorized` produce no UI at all — the app just stops answering. A defect, not a gap. | XS | 9a |
| 8 | Auto-ask fires on any interviewer utterance | No question classification: greetings, "can you hear me?", thinking aloud all trigger a full Opus call. Cost and noise. | M | — |
| 9 | Fixed 3-slot knowledge base | Competitors allow dozens of documents. Also: our 8,000-token ceiling is display-only and enforces nothing. | M | — |
| 10 | No overlay resize / opacity / click-through / position memory | The window permanently blocks a 460×620 rectangle of the meeting UI and reopens at the OS default every launch. | M | — |
| 11 | No tone / verbosity / persona control | Cluely sells "playbooks". One hard-coded voice for us. | S | — |
| 12 | No device picker, mute, or level meter | "Which mic am I using?" is currently answerable only by dumping a WAV and opening Audacity. | S | — |
| 13 | No sign-out, tray, auto-update, or usage display | Ordinary product hygiene, all absent. | M | — |

Items 8–13 are real and deliberately deferred; they are recorded here so they are
not rediscovered as surprises.

---

## Stealth and anti-proctoring — analysis, not a plan

ParakeetAI advertises, in its own marketing copy: invisible on screen share,
invisible in dock/taskbar, **invisible in Task Manager**, invisible to
tab-switching, **cursor undetectability**, and **undetectable to proctoring
software**. Cluely puts a milder version of this behind its $149.99/mo tier.

We ship only the first of those, and [`002`](002-implementation-phases.md) lists
anti-proctoring evasion as *"permanently out of scope, not deferred."* This
section records why that line should stay where it is.

**What the claims would actually require.** Hiding from screen share is a
supported Windows API — one flag, documented, stable. Everything else on that
list is not: hiding a process from Task Manager means interfering with process
enumeration; defeating tab-switch and cursor detection means hooking input and
window-focus events; defeating proctoring software means specifically
countermeasuring named security products. That is not a harder version of what we
already do — it is a different category of software, and it is the category
antivirus vendors write signatures for.

**Their own FAQ concedes it does not fully work.** Parakeet's support pages admit
the `pmodule` process name cannot be hidden in Activity Monitor, and that Zoom
invisibility requires the user to manually enable "Advanced capture with window
filtering." The marketing promises more than the product delivers, which is worth
noting before treating the feature list as a target.

**The costs are asymmetric.** An evasion arms race is permanent maintenance —
every proctoring update is a P0. It forecloses any enterprise or education
customer and any regulated industry. And it converts a defensible product ("an
overlay you can see and they can't, on your own machine") into one whose selling
point is defeating someone else's security control. Note the same reviews that
list Parakeet's stealth features also flag no G2 listing, no Trustpilot page, a
single named operator, and no security disclosures — the market prices that
reputation.

**What is worth taking from the comparison anyway.** Two of Parakeet's stealth
claims are ordinary UX we lack for ordinary reasons and could add without going
anywhere near evasion: `skipTaskbar` is already set, but we have **no tray icon
and no in-app quit**, and the overlay **cannot be made click-through**, so it
blocks the meeting UI underneath. Those are usability wins that happen to look
like stealth wins.

**Recommendation: leave the non-goal exactly as written.** Capture invisibility
via the documented Windows API, and nothing beyond it.

---

## What this became

Phase 9 in [`002-implementation-phases.md`](002-implementation-phases.md): gaps
1–4 plus the two defects (6 and 7), in four tracks — ask bar and follow-up
memory, language, coding mode and Markdown, post-call notes.

Gaps 8–13 stay on this list, unscheduled.

---

## Sources

Competitor capability claims are taken from vendor marketing and third-party
reviews, not from testing the products. Latency and accuracy figures in
particular are **vendor claims** — Parakeet publishes no benchmark, and Cluely's
"300 ms" measures a different quantity than our budget does.

- [cluely.com](https://cluely.com) — features, platforms, language count
- [docs.cluely.com — Live Insights](https://docs.cluely.com/feature/liveinsights) — Ask AI chat bar, default actions, playbooks
- [Cluely AI Pricing 2026 — FinalRound](https://www.finalroundai.com/blog/cluely-pricing) — tier breakdown and the $149.99 undetectability plan
- [Cluely Review — tldv](https://tldv.io/blog/cluely-review/) — follow-ups, custom prompts, document upload
- [parakeet-ai.com](https://parakeet-ai.com/) — features, models, upload limits, stealth claims, pricing shape
- [Parakeet AI Review 2026 — Interview Sidekick](https://interviewsidekick.com/blog/parakeet-ai-review) — 2–5 s latency observation, `pmodule` admission, "no mock interview mode"
- [What Is Parakeet AI — FinalRound](https://www.finalroundai.com/blog/what-is-parakeet-ai) — coding-interview support, 52 languages
- [Parakeet AI Review 2026 — PhantomCodeAI](https://www.phantomcodeai.com/blogs/parakeet-ai-review-2026) — platform coverage
