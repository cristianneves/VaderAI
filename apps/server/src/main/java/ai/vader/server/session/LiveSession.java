package ai.vader.server.session;

import ai.vader.server.llm.AnswerEngine;
import ai.vader.server.llm.AnswerMode;
import ai.vader.server.llm.AnswerRequest;
import ai.vader.server.limit.ModelCallLimiter;
import ai.vader.server.persistence.Answer;
import ai.vader.server.persistence.AnswerTrigger;
import ai.vader.server.persistence.TranscriptTurn;
import ai.vader.server.preferences.Language;
import ai.vader.server.prompt.PromptAssembler;
import ai.vader.server.protocol.ServerMessage;
import ai.vader.server.protocol.ServerMessage.ErrorCode;
import ai.vader.server.stt.SttProvider;
import ai.vader.server.stt.TranscriptEvent;
import ai.vader.server.turn.TurnDetector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Per-connection state. The socket handed in here is already wrapped in a
 * {@code ConcurrentWebSocketSessionDecorator} — transcript deltas and (from
 * Phase 4) answer tokens are produced by different threads, and concurrent
 * sends on a raw session corrupt the frame stream.
 */
final class LiveSession {

    private static final Logger log = LoggerFactory.getLogger(LiveSession.class);
    /** Finalized turns are written in batches; per-word writes would hammer the DB. */
    private static final int BATCH_SIZE = 20;
    private static final int CONTEXT_TURNS = 50;
    /**
     * How many completed answers are replayed to the model as prior turns. These
     * sit after the cache breakpoint, so every one of them is billed in full on
     * every subsequent question — three is enough for "explain that again" to
     * work without the request growing all session.
     */
    static final int MEMORY_EXCHANGES = 3;
    /**
     * Logged when an answer completed without producing a single delta. A
     * sentinel rather than a zero, so an empty answer cannot drag the median
     * of a metric that exists to be read in aggregate.
     */
    private static final long NO_FIRST_TOKEN = -1;

    private final WebSocketSession socket;
    private final ObjectMapper json;
    private final TranscriptService transcripts;
    private final AnswerEngine answers;
    private final PromptAssembler prompts;
    private final ModelCallLimiter limits;
    private final TranscriptRingBuffer recent = new TranscriptRingBuffer(CONTEXT_TURNS);
    private final TurnDetector turns = new TurnDetector();
    private final List<TranscriptTurn> pending = new ArrayList<>();
    private final Deque<AnswerRequest.Exchange> memory = new ArrayDeque<>();
    private final AtomicReference<AnswerEngine.AnswerStream> inFlight = new AtomicReference<>();

    private volatile UUID userId;
    private volatile UUID sessionId;
    private volatile SttProvider stt;
    private volatile String knowledgeBase = "";
    private volatile Language language = Language.DEFAULT;
    private volatile ScheduledFuture<?> authDeadline;

    LiveSession(
            WebSocketSession socket,
            ObjectMapper json,
            TranscriptService transcripts,
            AnswerEngine answers,
            PromptAssembler prompts,
            ModelCallLimiter limits) {
        this.socket = socket;
        this.json = json;
        this.transcripts = transcripts;
        this.answers = answers;
        this.prompts = prompts;
        this.limits = limits;
    }

    TurnDetector turns() {
        return turns;
    }

    /**
     * Streams one answer. A trigger arriving while an answer is still streaming
     * cancels it — the previous question is stale the moment a new one lands.
     *
     * <p>The deltas are accumulated as well as sent, so the session can be
     * reviewed after the call. Only a completed answer is stored: cancelling is
     * deliberate, and a truncated answer that was replaced on purpose is not
     * worth keeping in the history. The same rule governs what enters the
     * follow-up memory — a cancelled answer is not something the model said.
     *
     * @param question what the user typed, or null to answer from the transcript
     * @param mode which prompt to answer with; a screenshot forces coding anyway
     */
    void ask(AnswerTrigger trigger, Optional<AnswerRequest.ImageInput> image, String question, AnswerMode mode) {
        // Before cancelInFlight, not after: a refused question must not take
        // down the answer already on screen. All three triggers — manual,
        // screenshot and auto — funnel through here, so this is the only check.
        if (!limits.tryAcquire(userId, System.currentTimeMillis())) {
            send(new ServerMessage.Failure(
                    ErrorCode.RATE_LIMITED,
                    "you have reached the hourly question limit — the next one will work in a little while"));
            return;
        }
        cancelInFlight();

        UUID answerId = UUID.randomUUID();
        // Started before assembly, which is on the critical path and belongs
        // inside the number.
        long askedAtNanos = System.nanoTime();
        var firstTokenMs = new AtomicLong(NO_FIRST_TOKEN);
        String asked = describeAsk(question, image.isPresent());
        var request =
                prompts.assemble(recent.snapshot(), knowledgeBase, image, question, memorySnapshot(), language, mode);
        // Local to this call, so two asks racing each other cannot append into
        // one another's text.
        var spoken = new StringBuilder();
        send(new ServerMessage.AnswerStart(answerId));

        inFlight.set(answers.stream(request, new AnswerEngine.Listener() {
            @Override
            public void onDelta(String text) {
                firstTokenMs.compareAndSet(NO_FIRST_TOKEN, (System.nanoTime() - askedAtNanos) / 1_000_000);
                // Append before sending: a send can fail on a slow client, and
                // the stored answer should be what the model produced rather
                // than what survived the socket.
                spoken.append(text);
                send(new ServerMessage.AnswerDelta(answerId, text));
            }

            @Override
            public void onComplete(AnswerEngine.AnswerUsage usage) {
                recordAnswer(spoken.toString(), trigger);
                remember(asked, spoken.toString());
                send(new ServerMessage.AnswerEnd(answerId));
                // ttftMs is this server's ask-to-first-delta, not the number the
                // product is sold on: end of question to first visible token also
                // contains TurnDetector.SILENCE_MS on the auto path and one hop
                // to the overlay. Read it as a floor. mode is here because a
                // screenshot carries ~1200 extra uncached input tokens, which
                // makes the figure meaningless without it.
                log.info(
                        "answer {} mode={} ttftMs={} tokens in={} out={} cacheRead={} cacheWrite={}",
                        answerId,
                        request.mode(),
                        firstTokenMs.get(),
                        usage.inputTokens(),
                        usage.outputTokens(),
                        usage.cacheReadInputTokens(),
                        usage.cacheCreationInputTokens());
            }

            @Override
            public void onError(Throwable cause) {
                // Nothing recorded: a failed answer was never given.
                log.warn("answer {} failed", answerId, cause);
                // A deployment missing its key is not something asking again
                // fixes, so it does not go out as a retryable model failure.
                send(cause instanceof AnswerEngine.NotConfiguredException
                        ? new ServerMessage.Failure(ErrorCode.INTERNAL, "the server is missing its model configuration")
                        : new ServerMessage.Failure(ErrorCode.LLM_FAILED, "could not generate an answer"));
                send(new ServerMessage.AnswerEnd(answerId));
            }
        }));
    }

    /**
     * What the model is told it was asked, when this answer is replayed as a
     * prior turn. Short by design — the transcript itself already travels in
     * every request, and repeating it once per remembered exchange would grow
     * the prompt quadratically over a session.
     */
    private String describeAsk(String question, boolean hasImage) {
        if (question != null && !question.isBlank()) return question.strip();
        if (hasImage) return "(asked about what was on the screen)";
        return recent.snapshot().reversed().stream()
                .filter(turn -> turn.channel() == TranscriptEvent.CHANNEL_INTERVIEWER)
                .findFirst()
                .map(TranscriptEvent::text)
                .orElse("(asked about the conversation so far)");
    }

    private void remember(String asked, String answer) {
        if (answer.isBlank()) return;
        synchronized (memory) {
            if (memory.size() == MEMORY_EXCHANGES) memory.removeFirst();
            memory.addLast(new AnswerRequest.Exchange(asked, answer));
        }
    }

    private List<AnswerRequest.Exchange> memorySnapshot() {
        synchronized (memory) {
            return List.copyOf(memory);
        }
    }

    private void recordAnswer(String content, AnswerTrigger trigger) {
        if (content.isBlank()) return;
        try {
            transcripts.saveAnswer(Answer.of(sessionId, userId, content, trigger));
        } catch (Exception failed) {
            // Losing a history row must not take down a live answer that the
            // user has already read on screen.
            log.warn("could not record answer on session {}", sessionId, failed);
        }
    }

    void cancelInFlight() {
        AnswerEngine.AnswerStream previous = inFlight.getAndSet(null);
        if (previous != null) previous.close();
    }

    /**
     * The knowledge base is captured once, here. Re-reading it mid-session would
     * change the cached prompt prefix underneath a live conversation and throw
     * the cache away; an edit takes effect on the next session instead.
     */
    void authenticated(UUID userId, UUID sessionId, SttProvider stt, String knowledgeBase, Language language) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.stt = stt;
        this.knowledgeBase = knowledgeBase;
        this.language = language;
        if (authDeadline != null) authDeadline.cancel(false);
    }

    void awaitAuth(ScheduledFuture<?> deadline) {
        this.authDeadline = deadline;
    }

    ScheduledFuture<?> authDeadline() {
        return authDeadline;
    }

    boolean isAuthenticated() {
        return userId != null;
    }

    UUID sessionId() {
        return sessionId;
    }

    UUID userId() {
        return userId;
    }

    SttProvider stt() {
        return stt;
    }

    /** The conversation context Phase 4's prompt assembler will read. */
    TranscriptRingBuffer recent() {
        return recent;
    }

    void send(ServerMessage message) {
        try {
            socket.sendMessage(new TextMessage(json.writeValueAsString(message)));
        } catch (Exception failed) {
            // A send failure means the client is gone or too slow; the decorator
            // has already given up on it. Nothing useful to do but note it.
            log.debug("send failed on session {}", sessionId, failed);
        }
    }

    /** Buffers a finalized turn and flushes to the database once a batch fills. */
    void recordFinal(TranscriptEvent event) {
        recent.add(event);
        List<TranscriptTurn> batch;
        synchronized (pending) {
            pending.add(TranscriptTurn.of(sessionId, userId, event.channel(), event.text()));
            if (pending.size() < BATCH_SIZE) return;
            batch = List.copyOf(pending);
            pending.clear();
        }
        transcripts.saveTurns(batch);
    }

    void flush() {
        List<TranscriptTurn> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = List.copyOf(pending);
            pending.clear();
        }
        transcripts.saveTurns(batch);
    }
}
