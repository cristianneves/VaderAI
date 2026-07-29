package ai.vader.server.session;

import ai.vader.server.protocol.ClientMessage;
import ai.vader.server.protocol.ServerMessage;
import ai.vader.server.protocol.ServerMessage.ErrorCode;
import ai.vader.server.stt.SttProvider;
import ai.vader.server.stt.SttProviderFactory;
import ai.vader.server.stt.TranscriptEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * The one WebSocket endpoint: binary frames carry audio up, JSON carries control
 * and results down.
 *
 * <p><b>First-frame authentication.</b> The WebSocket API cannot set an
 * Authorization header, so the client sends {@code hello} with its Supabase
 * access token as the very first message. A connection that has not
 * authenticated within {@link #AUTH_DEADLINE_SECONDS} seconds is closed with
 * 1008. Audio arriving before that is discarded.
 *
 * <p>This class must never reference a speech vendor's types — it talks to
 * {@link SttProvider} only.
 */
@Component
public class SessionWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionWebSocketHandler.class);
    private static final int AUTH_DEADLINE_SECONDS = 5;
    private static final int PROTOCOL_VERSION = 1;
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    /** A client too slow to drain this much is dropped rather than buffered forever. */
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("auth-deadline").factory());

    private final JwtDecoder jwtDecoder;
    private final SttProviderFactory sttProviders;
    private final TranscriptService transcripts;
    private final ObjectMapper json;

    SessionWebSocketHandler(
            JwtDecoder jwtDecoder,
            SttProviderFactory sttProviders,
            TranscriptService transcripts,
            ObjectMapper json) {
        this.jwtDecoder = jwtDecoder;
        this.sttProviders = sttProviders;
        this.transcripts = transcripts;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        var socket = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        var live = new LiveSession(socket, json, transcripts);
        sessions.put(session.getId(), live);
        live.awaitAuth(timers.schedule(
                () -> closeUnauthenticated(session), AUTH_DEADLINE_SECONDS, TimeUnit.SECONDS));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        LiveSession live = sessions.get(session.getId());
        if (live == null) return;

        ClientMessage parsed;
        try {
            parsed = json.readValue(message.getPayload(), ClientMessage.class);
        } catch (Exception malformed) {
            live.send(new ServerMessage.Failure(ErrorCode.BAD_REQUEST, "unreadable message"));
            return;
        }

        switch (parsed) {
            case ClientMessage.Hello hello -> onHello(session, live, hello);
            case ClientMessage.Ping ignored -> live.send(new ServerMessage.Pong());
            // Triggering an answer is Phase 4; the messages are accepted now so
            // the client can be written against the final protocol.
            case ClientMessage.Ask ignored -> requireAuth(session, live);
            case ClientMessage.Screenshot ignored -> requireAuth(session, live);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        LiveSession live = sessions.get(session.getId());
        if (live == null) return;
        if (!live.isAuthenticated()) {
            close(session, CloseStatus.POLICY_VIOLATION.withReason("audio before hello"));
            return;
        }
        live.stt().write(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        LiveSession live = sessions.remove(session.getId());
        if (live == null) return;

        var deadline = live.authDeadline();
        if (deadline != null) deadline.cancel(false);
        if (live.stt() != null) live.stt().close();
        live.flush();
        if (live.isAuthenticated()) transcripts.closeSession(live.sessionId(), live.userId());
        log.debug("session {} closed: {}", live.sessionId(), status);
    }

    private void onHello(WebSocketSession session, LiveSession live, ClientMessage.Hello hello) {
        if (live.isAuthenticated()) {
            live.send(new ServerMessage.Failure(ErrorCode.BAD_REQUEST, "already authenticated"));
            return;
        }
        if (hello.protocolVersion() != PROTOCOL_VERSION) {
            live.send(new ServerMessage.Failure(
                    ErrorCode.BAD_REQUEST, "unsupported protocol version " + hello.protocolVersion()));
            close(session, CloseStatus.POLICY_VIOLATION.withReason("protocol version"));
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(jwtDecoder.decode(hello.accessToken()).getSubject());
        } catch (JwtException | IllegalArgumentException rejected) {
            log.debug("rejected hello", rejected);
            live.send(new ServerMessage.Failure(ErrorCode.UNAUTHORIZED, "invalid access token"));
            close(session, CloseStatus.POLICY_VIOLATION.withReason("invalid token"));
            return;
        }

        UUID sessionId = transcripts.openSession(userId).id();
        SttProvider stt = sttProviders.create();
        live.authenticated(userId, sessionId, stt);
        stt.start(new SttProvider.Listener() {
            @Override
            public void onTranscript(TranscriptEvent event) {
                live.send(new ServerMessage.Transcript(event.channel(), event.text(), event.isFinal()));
                if (event.isFinal()) live.recordFinal(event);
            }

            @Override
            public void onError(Throwable cause) {
                log.warn("transcription failed on session {}", sessionId, cause);
                live.send(new ServerMessage.Failure(ErrorCode.STT_FAILED, "transcription unavailable"));
            }
        });
        live.send(new ServerMessage.Ready(sessionId));
    }

    private void requireAuth(WebSocketSession session, LiveSession live) {
        if (live.isAuthenticated()) return;
        live.send(new ServerMessage.Failure(ErrorCode.UNAUTHORIZED, "hello first"));
        close(session, CloseStatus.POLICY_VIOLATION.withReason("not authenticated"));
    }

    private void closeUnauthenticated(WebSocketSession session) {
        LiveSession live = sessions.get(session.getId());
        if (live == null || live.isAuthenticated()) return;
        live.send(new ServerMessage.Failure(
                ErrorCode.UNAUTHORIZED, "No valid hello frame within " + AUTH_DEADLINE_SECONDS + "s"));
        close(session, CloseStatus.POLICY_VIOLATION.withReason("no hello"));
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            log.debug("close failed", ignored);
        }
    }
}
