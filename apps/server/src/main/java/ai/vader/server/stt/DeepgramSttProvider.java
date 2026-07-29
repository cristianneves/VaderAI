package ai.vader.server.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams PCM16 to Deepgram over a WebSocket and reports transcripts back.
 *
 * <p>Two channels are sent as one interleaved stream with {@code multichannel=true},
 * so Deepgram transcribes each independently and tells us which channel each
 * result came from — that is the whole speaker-attribution mechanism.
 */
public final class DeepgramSttProvider implements SttProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepgramSttProvider.class);
    private static final String PARAMS =
            "?model=nova-3&encoding=linear16&sample_rate=16000&channels=2&multichannel=true"
                    + "&interim_results=true&endpointing=700&punctuate=true";
    private static final ByteString CLOSE_STREAM = ByteString.encodeUtf8("{\"type\":\"CloseStream\"}");

    private final OkHttpClient http;
    private final ObjectMapper json;
    private final DeepgramProperties properties;

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger dropped = new AtomicInteger();
    private volatile Listener listener;
    private volatile int attempt;

    DeepgramSttProvider(OkHttpClient http, ObjectMapper json, DeepgramProperties properties) {
        this.http = http;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        connect();
    }

    private void connect() {
        var request = new Request.Builder()
                .url(properties.url() + PARAMS)
                .header("Authorization", "Token " + properties.apiKey())
                .build();
        socket.set(http.newWebSocket(request, new DeepgramListener()));
    }

    @Override
    public void write(ByteBuffer audio) {
        WebSocket current = socket.get();
        // While reconnecting there is nowhere to put audio. Dropping it keeps
        // the session live and only costs the words spoken during the gap;
        // queueing it would grow without bound and desynchronise the stream.
        if (current == null || !current.send(ByteString.of(audio))) {
            int total = dropped.incrementAndGet();
            if (total % 50 == 1) log.warn("dropped {} audio frames — Deepgram socket unavailable", total);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        WebSocket current = socket.getAndSet(null);
        if (current != null) {
            // Tells Deepgram to flush and finalize rather than truncating.
            current.send(CLOSE_STREAM);
            current.close(1000, "session ended");
        }
    }

    private void scheduleReconnect(Throwable cause) {
        if (closed.get()) return;
        if (++attempt > properties.reconnectAttempts()) {
            listener.onError(new IllegalStateException(
                    "Deepgram unavailable after " + properties.reconnectAttempts() + " attempts", cause));
            return;
        }
        long backoffMs = 500L << (attempt - 1);
        log.warn("Deepgram socket lost, reconnect {} in {} ms", attempt, backoffMs, cause);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!closed.get()) connect();
        });
    }

    private final class DeepgramListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            attempt = 0;
            log.debug("Deepgram socket open");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                DeepgramMessages.parse(json.readTree(text)).ifPresent(listener::onTranscript);
            } catch (Exception malformed) {
                log.warn("unparseable Deepgram message", malformed);
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            // OkHttp does not answer a close frame on its own. Without this the
            // handshake never completes, onClosed never fires, and a dropped
            // Deepgram socket would go unnoticed until the session ended.
            webSocket.close(1000, null);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable cause, Response response) {
            socket.compareAndSet(webSocket, null);
            scheduleReconnect(cause);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            socket.compareAndSet(webSocket, null);
            if (!closed.get()) scheduleReconnect(new IllegalStateException("closed " + code + " " + reason));
        }
    }
}
