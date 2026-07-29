package ai.vader.server.session;

import ai.vader.server.llm.AnswerEngine;
import ai.vader.server.llm.AnswerRequest;
import ai.vader.server.persistence.TranscriptTurn;
import ai.vader.server.prompt.PromptAssembler;
import ai.vader.server.protocol.ServerMessage;
import ai.vader.server.protocol.ServerMessage.ErrorCode;
import ai.vader.server.stt.SttProvider;
import ai.vader.server.stt.TranscriptEvent;
import ai.vader.server.turn.TurnDetector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
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

    private final WebSocketSession socket;
    private final ObjectMapper json;
    private final TranscriptService transcripts;
    private final AnswerEngine answers;
    private final PromptAssembler prompts;
    private final TranscriptRingBuffer recent = new TranscriptRingBuffer(CONTEXT_TURNS);
    private final TurnDetector turns = new TurnDetector();
    private final List<TranscriptTurn> pending = new ArrayList<>();
    private final AtomicReference<AnswerEngine.AnswerStream> inFlight = new AtomicReference<>();

    private volatile UUID userId;
    private volatile UUID sessionId;
    private volatile SttProvider stt;
    private volatile ScheduledFuture<?> authDeadline;

    LiveSession(
            WebSocketSession socket,
            ObjectMapper json,
            TranscriptService transcripts,
            AnswerEngine answers,
            PromptAssembler prompts) {
        this.socket = socket;
        this.json = json;
        this.transcripts = transcripts;
        this.answers = answers;
        this.prompts = prompts;
    }

    TurnDetector turns() {
        return turns;
    }

    /**
     * Streams one answer. A trigger arriving while an answer is still streaming
     * cancels it — the previous question is stale the moment a new one lands.
     */
    void ask(Optional<AnswerRequest.ImageInput> image) {
        cancelInFlight();

        UUID answerId = UUID.randomUUID();
        // The knowledge base is empty until Phase 5; the cached prefix is already
        // shaped to hold it.
        var request = prompts.assemble(recent.snapshot(), "", image);
        send(new ServerMessage.AnswerStart(answerId));

        inFlight.set(answers.stream(request, new AnswerEngine.Listener() {
            @Override
            public void onDelta(String text) {
                send(new ServerMessage.AnswerDelta(answerId, text));
            }

            @Override
            public void onComplete(AnswerEngine.AnswerUsage usage) {
                send(new ServerMessage.AnswerEnd(answerId));
                log.info(
                        "answer {} tokens in={} out={} cacheRead={} cacheWrite={}",
                        answerId,
                        usage.inputTokens(),
                        usage.outputTokens(),
                        usage.cacheReadInputTokens(),
                        usage.cacheCreationInputTokens());
            }

            @Override
            public void onError(Throwable cause) {
                log.warn("answer {} failed", answerId, cause);
                send(new ServerMessage.Failure(ErrorCode.LLM_FAILED, "could not generate an answer"));
                send(new ServerMessage.AnswerEnd(answerId));
            }
        }));
    }

    void cancelInFlight() {
        AnswerEngine.AnswerStream previous = inFlight.getAndSet(null);
        if (previous != null) previous.close();
    }

    void authenticated(UUID userId, UUID sessionId, SttProvider stt) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.stt = stt;
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
