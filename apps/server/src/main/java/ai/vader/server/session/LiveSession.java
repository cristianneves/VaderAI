package ai.vader.server.session;

import ai.vader.server.persistence.TranscriptTurn;
import ai.vader.server.protocol.ServerMessage;
import ai.vader.server.stt.SttProvider;
import ai.vader.server.stt.TranscriptEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
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
    private final TranscriptRingBuffer recent = new TranscriptRingBuffer(CONTEXT_TURNS);
    private final List<TranscriptTurn> pending = new ArrayList<>();

    private volatile UUID userId;
    private volatile UUID sessionId;
    private volatile SttProvider stt;
    private volatile ScheduledFuture<?> authDeadline;

    LiveSession(WebSocketSession socket, ObjectMapper json, TranscriptService transcripts) {
        this.socket = socket;
        this.json = json;
        this.transcripts = transcripts;
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
