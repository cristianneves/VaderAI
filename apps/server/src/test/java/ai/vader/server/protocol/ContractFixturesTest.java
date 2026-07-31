package ai.vader.server.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Java half of the wire contract. {@code packages/protocol} is the source of
 * truth and the fixtures are the executable check that this mirror has not
 * drifted — a field renamed on either side fails on the other.
 */
class ContractFixturesTest {

    private static final Path FIXTURES = Path.of("..", "..", "contracts", "messages");

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode fixture(String file, String name) throws Exception {
        JsonNode all = json.readTree(Files.readString(FIXTURES.resolve(file)));
        assertThat(all.has(name)).as("fixture %s in %s", name, file).isTrue();
        return all.get(name);
    }

    private <T> T read(String file, String name, Class<T> type) throws Exception {
        return json.treeToValue(fixture(file, name), type);
    }

    @Test
    void helloFixtureDeserializes() throws Exception {
        var hello = (ClientMessage.Hello) read("client.json", "hello", ClientMessage.class);
        assertThat(hello.protocolVersion()).isEqualTo(1);
        assertThat(hello.accessToken()).isNotBlank();
    }

    @Test
    void askFixtureDeserializes() throws Exception {
        var ask = (ClientMessage.Ask) read("client.json", "ask", ClientMessage.class);
        assertThat(ask.trigger()).isEqualTo(ClientMessage.Trigger.MANUAL);
        assertThat(ask.question()).isEqualTo("Explain that last answer more simply.");
    }

    @Test
    void anAskWithNoQuestionStillDeserializes() throws Exception {
        // What the auto-trigger and a bare Ctrl+Enter send.
        var ask = (ClientMessage.Ask) json.readValue("{\"type\":\"ask\",\"trigger\":\"auto\"}", ClientMessage.class);

        assertThat(ask.trigger()).isEqualTo(ClientMessage.Trigger.AUTO);
        assertThat(ask.question()).isNull();
    }

    @Test
    void screenshotFixtureDeserializes() throws Exception {
        var shot = (ClientMessage.Screenshot) read("client.json", "screenshot", ClientMessage.class);
        assertThat(shot.mimeType()).isEqualTo("image/png");
        assertThat(shot.dataBase64()).isNotBlank();
    }

    @Test
    void pingFixtureDeserializes() throws Exception {
        assertThat(read("client.json", "ping", ClientMessage.class)).isInstanceOf(ClientMessage.Ping.class);
    }

    @Test
    void readyFixtureDeserializes() throws Exception {
        var ready = (ServerMessage.Ready) read("server.json", "ready", ServerMessage.class);
        assertThat(ready.sessionId()).isNotNull();
    }

    @Test
    void transcriptFixtureDeserializes() throws Exception {
        var transcript = (ServerMessage.Transcript) read("server.json", "transcript", ServerMessage.class);
        assertThat(transcript.channel()).isZero();
        assertThat(transcript.isFinal()).isTrue();
        assertThat(transcript.text()).isNotBlank();
    }

    @Test
    void answerFixturesDeserialize() throws Exception {
        assertThat(read("server.json", "answer_start", ServerMessage.class))
                .isInstanceOf(ServerMessage.AnswerStart.class);
        var delta = (ServerMessage.AnswerDelta) read("server.json", "answer_delta", ServerMessage.class);
        assertThat(delta.text()).isNotBlank();
        assertThat(read("server.json", "answer_end", ServerMessage.class))
                .isInstanceOf(ServerMessage.AnswerEnd.class);
    }

    @Test
    void errorFixtureDeserializes() throws Exception {
        var failure = (ServerMessage.Failure) read("server.json", "error", ServerMessage.class);
        assertThat(failure.code()).isEqualTo(ServerMessage.ErrorCode.UNAUTHORIZED);
    }

    @Test
    void pongFixtureDeserializes() throws Exception {
        assertThat(read("server.json", "pong", ServerMessage.class)).isInstanceOf(ServerMessage.Pong.class);
    }

    @Test
    void serializedServerMessagesMatchTheFixtureShape() throws Exception {
        var id = UUID.fromString("6f1c9a2e-7b3d-4c8e-9f10-2a5b6c7d8e90");
        JsonNode written = json.valueToTree(new ServerMessage.Ready(id));
        assertThat(written.get("type").asText()).isEqualTo("ready");
        assertThat(written.get("sessionId").asText()).isEqualTo(id.toString());

        // isFinal must stay isFinal — Jackson renaming it would silently break
        // every client that reads the flag.
        JsonNode transcript = json.valueToTree(new ServerMessage.Transcript(1, "hi", false));
        assertThat(transcript.fieldNames()).toIterable().containsExactlyInAnyOrder("type", "channel", "text", "isFinal");
        assertThat(transcript.get("type").asText()).isEqualTo("transcript");

        JsonNode failure = json.valueToTree(
                new ServerMessage.Failure(ServerMessage.ErrorCode.STT_FAILED, "gone"));
        assertThat(failure.get("type").asText()).isEqualTo("error");
        assertThat(failure.get("code").asText()).isEqualTo("stt_failed");
    }

    @Test
    void unknownMessageTypeIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> json.readValue("{\"type\":\"nope\"}", ClientMessage.class))
                .isInstanceOf(Exception.class);
    }
}
