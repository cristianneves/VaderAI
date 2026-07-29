package ai.vader.server.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import ai.vader.server.persistence.SessionRow;
import ai.vader.server.stt.SttProvider;
import ai.vader.server.stt.SttProviderFactory;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * First-frame authentication, end to end over a real socket.
 *
 * <p>The JWT decoder and the STT provider are stubbed: this is about the
 * handshake contract, not about Supabase or Deepgram.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionWebSocketHandlerTest {

    private static final String GOOD_TOKEN = "good-token";
    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SESSION_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private SttProviderFactory sttProviders;

    @MockitoBean
    private TranscriptService transcripts;

    private final List<ByteBuffer> audioSentToStt = new CopyOnWriteArrayList<>();

    @BeforeEach
    void stubCollaborators() {
        given(jwtDecoder.decode(GOOD_TOKEN))
                .willReturn(Jwt.withTokenValue(GOOD_TOKEN)
                        .header("alg", "ES256")
                        .subject(USER_ID.toString())
                        .claim("sub", USER_ID.toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build());
        given(jwtDecoder.decode("bad-token")).willThrow(new JwtException("bad signature"));
        given(transcripts.openSession(USER_ID))
                .willReturn(new SessionRow(SESSION_ID, USER_ID, Instant.now(), null));
        given(sttProviders.create()).willReturn(new SttProvider() {
            @Override
            public void start(Listener listener) {}

            @Override
            public void write(ByteBuffer audio) {
                audioSentToStt.add(audio);
            }

            @Override
            public void close() {}
        });
    }

    private Client connect() throws Exception {
        var client = new Client();
        new StandardWebSocketClient()
                .execute(client, "ws://localhost:" + port + "/v1/session")
                .get();
        return client;
    }

    @Test
    void helloWithAValidTokenIsAcknowledgedWithReady() throws Exception {
        var client = connect();

        client.send("{\"type\":\"hello\",\"protocolVersion\":1,\"accessToken\":\"" + GOOD_TOKEN + "\"}");

        await().atMost(Duration.ofSeconds(5)).until(() -> !client.messages.isEmpty());
        assertThat(client.messages.get(0)).contains("\"type\":\"ready\"").contains(SESSION_ID.toString());
    }

    @Test
    void connectionWithoutHelloIsClosedWith1008() throws Exception {
        var client = connect();

        // The deadline is 5s; allow margin without making the test slow to fail.
        await().atMost(Duration.ofSeconds(12)).until(() -> client.closeStatus.get() != null);
        assertThat(client.closeStatus.get().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(client.messages).anyMatch(m -> m.contains("\"code\":\"unauthorized\""));
    }

    @Test
    void helloWithAnInvalidTokenIsRejected() throws Exception {
        var client = connect();

        client.send("{\"type\":\"hello\",\"protocolVersion\":1,\"accessToken\":\"bad-token\"}");

        await().atMost(Duration.ofSeconds(5)).until(() -> client.closeStatus.get() != null);
        assertThat(client.messages).anyMatch(m -> m.contains("\"code\":\"unauthorized\""));
        assertThat(client.closeStatus.get().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void audioBeforeHelloIsRefused() throws Exception {
        var client = connect();

        client.sendBinary(new byte[6400]);

        await().atMost(Duration.ofSeconds(5)).until(() -> client.closeStatus.get() != null);
        assertThat(client.closeStatus.get().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(audioSentToStt).isEmpty();
    }

    @Test
    void audioAfterHelloReachesTheProvider() throws Exception {
        var client = connect();
        client.send("{\"type\":\"hello\",\"protocolVersion\":1,\"accessToken\":\"" + GOOD_TOKEN + "\"}");
        await().atMost(Duration.ofSeconds(5)).until(() -> !client.messages.isEmpty());

        client.sendBinary(new byte[6400]);

        await().atMost(Duration.ofSeconds(5)).until(() -> !audioSentToStt.isEmpty());
        assertThat(audioSentToStt.get(0).remaining()).isEqualTo(6400);
    }

    @Test
    void anUnsupportedProtocolVersionIsRejected() throws Exception {
        var client = connect();

        client.send("{\"type\":\"hello\",\"protocolVersion\":99,\"accessToken\":\"" + GOOD_TOKEN + "\"}");

        await().atMost(Duration.ofSeconds(5)).until(() -> client.closeStatus.get() != null);
        assertThat(client.messages).anyMatch(m -> m.contains("\"code\":\"bad_request\""));
    }

    @Test
    void pingIsAnsweredWithPong() throws Exception {
        var client = connect();

        client.send("{\"type\":\"ping\"}");

        await().atMost(Duration.ofSeconds(5)).until(() -> !client.messages.isEmpty());
        assertThat(client.messages.get(0)).contains("\"type\":\"pong\"");
    }

    @Test
    void anUnreadableMessageIsReportedWithoutClosing() throws Exception {
        var client = connect();

        client.send("not json at all");

        await().atMost(Duration.ofSeconds(5)).until(() -> !client.messages.isEmpty());
        assertThat(client.messages.get(0)).contains("\"code\":\"bad_request\"");
        assertThat(client.closeStatus.get()).isNull();
    }

    private static final class Client extends AbstractWebSocketHandler {

        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        private final AtomicReference<WebSocketSession> session = new AtomicReference<>();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            this.session.set(session);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closeStatus.set(status);
        }

        void send(String payload) throws Exception {
            session.get().sendMessage(new TextMessage(payload));
        }

        void sendBinary(byte[] payload) throws Exception {
            session.get().sendMessage(new BinaryMessage(payload));
        }
    }
}
