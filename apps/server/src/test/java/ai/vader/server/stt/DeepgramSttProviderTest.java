package ai.vader.server.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the provider against a local WebSocket that speaks Deepgram's frame
 * format. No API key involved — this is about the mapping and the socket
 * lifecycle, which is what breaks.
 */
class DeepgramSttProviderTest {

    private MockWebServer server;
    private String url;
    private final List<TranscriptEvent> received = new CopyOnWriteArrayList<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final List<ByteString> uplink = new CopyOnWriteArrayList<>();

    private final SttProvider.Listener listener = new SttProvider.Listener() {
        @Override
        public void onTranscript(TranscriptEvent event) {
            received.add(event);
        }

        @Override
        public void onError(Throwable cause) {
            errors.add(cause);
        }
    };

    /** Answers the close frame, so shutting a socket down actually completes. */
    private abstract static class ClosingListener extends WebSocketListener {
        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            socket.close(1000, null);
        }
    }

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
        // Captured up front: getPort() on a shut-down server does not give the
        // port back, and two tests deliberately run without a server.
        url = "ws://" + server.getHostName() + ":" + server.getPort() + "/v1/listen";
    }

    @AfterEach
    void stopServer() {
        try {
            server.shutdown();
        } catch (Exception alreadyStopped) {
            // Two tests shut it down themselves.
        }
    }

    private DeepgramSttProvider provider(int reconnectAttempts) {
        return provider(reconnectAttempts, "en");
    }

    private DeepgramSttProvider provider(int reconnectAttempts, String languageCode) {
        return new DeepgramSttProvider(
                new OkHttpClient(),
                new ObjectMapper(),
                new DeepgramProperties("test-key", url, reconnectAttempts),
                languageCode);
    }

    @Test
    void sendsTheSessionLanguageToDeepgram() {
        assertThat(provider(1, "pt-BR").requestUrl())
                .startsWith(url)
                .contains("model=nova-3")
                .contains("multichannel=true")
                .endsWith("&language=pt-BR");
    }

    @Test
    void multilingualIsJustAnotherLanguageCodeOnTheWire() {
        assertThat(provider(1, "multi").requestUrl()).endsWith("&language=multi");
    }

    private void enqueueSocket(WebSocketListener behaviour) {
        server.enqueue(new MockResponse().withWebSocketUpgrade(behaviour));
    }

    @Test
    void relaysTranscriptsWithTheirChannel() {
        enqueueSocket(new ClosingListener() {
            @Override
            public void onOpen(WebSocket socket, okhttp3.Response response) {
                socket.send("""
                        {"type":"Results","channel_index":[0,2],"is_final":false,
                         "channel":{"alternatives":[{"transcript":"tell me about"}]}}""");
                socket.send("""
                        {"type":"Results","channel_index":[1,2],"is_final":true,
                         "channel":{"alternatives":[{"transcript":"I shipped it"}]}}""");
            }
        });

        var provider = provider(3);
        provider.start(listener);

        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 2);
        assertThat(received.get(0))
                .isEqualTo(new TranscriptEvent(TranscriptEvent.CHANNEL_INTERVIEWER, "tell me about", false));
        assertThat(received.get(1))
                .isEqualTo(new TranscriptEvent(TranscriptEvent.CHANNEL_USER, "I shipped it", true));
        provider.close();
    }

    @Test
    void sendsTheApiKeyAndStreamingParameters() throws Exception {
        enqueueSocket(new ClosingListener() {});

        var provider = provider(3);
        provider.start(listener);

        var request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Token test-key");
        assertThat(request.getPath())
                .contains("model=nova-3")
                .contains("encoding=linear16")
                .contains("sample_rate=16000")
                .contains("channels=2")
                .contains("multichannel=true")
                .contains("interim_results=true")
                .contains("endpointing=700");
        provider.close();
    }

    @Test
    void forwardsAudioFramesUpstream() {
        enqueueSocket(new ClosingListener() {
            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                uplink.add(bytes);
            }
        });

        var provider = provider(3);
        provider.start(listener);
        await().atMost(Duration.ofSeconds(5)).until(() -> server.getRequestCount() == 1);
        provider.write(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));

        await().atMost(Duration.ofSeconds(5)).until(() -> uplink.size() == 1);
        assertThat(uplink.get(0).toByteArray()).containsExactly(1, 2, 3, 4);
        provider.close();
    }

    @Test
    void reconnectsWhenTheSocketDrops() {
        // First socket closes on its own; the provider should come back on a second.
        enqueueSocket(new ClosingListener() {
            @Override
            public void onOpen(WebSocket socket, okhttp3.Response response) {
                socket.close(1011, "server going away");
            }
        });
        enqueueSocket(new ClosingListener() {
            @Override
            public void onOpen(WebSocket socket, okhttp3.Response response) {
                socket.send("""
                        {"type":"Results","channel_index":[0,2],"is_final":true,
                         "channel":{"alternatives":[{"transcript":"back"}]}}""");
            }
        });

        var provider = provider(3);
        provider.start(listener);

        await().atMost(Duration.ofSeconds(10)).until(() -> !received.isEmpty());
        assertThat(received.get(0).text()).isEqualTo("back");
        assertThat(errors).isEmpty();
        provider.close();
    }

    @Test
    void reportsFailureAfterExhaustingReconnects() throws Exception {
        server.shutdown(); // nothing is listening at all

        var provider = provider(1);
        provider.start(listener);

        await().atMost(Duration.ofSeconds(10)).until(() -> !errors.isEmpty());
        assertThat(errors.get(0)).hasMessageContaining("Deepgram unavailable");
        provider.close();
    }

    @Test
    void dropsAudioWhenNoSocketIsAvailable() throws Exception {
        server.shutdown();

        var provider = provider(1);
        provider.start(listener);
        // Must not throw or block — the session stays alive through an outage.
        provider.write(ByteBuffer.wrap(new byte[] {1, 2}));

        assertThat(received).isEmpty();
        provider.close();
    }
}
