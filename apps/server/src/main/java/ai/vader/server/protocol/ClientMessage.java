package ai.vader.server.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Client → server control messages. Mirrors the zod schemas in
 * {@code packages/protocol}, which are the source of truth. The fixtures in
 * {@code contracts/messages/client.json} are asserted by both sides, so a field
 * rename here fails the TypeScript test and vice versa.
 *
 * <p>Audio does not travel as JSON — it arrives as raw binary frames.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ClientMessage.Hello.class, name = "hello"),
    @JsonSubTypes.Type(value = ClientMessage.Ask.class, name = "ask"),
    @JsonSubTypes.Type(value = ClientMessage.Screenshot.class, name = "screenshot"),
    @JsonSubTypes.Type(value = ClientMessage.Ping.class, name = "ping")
})
public sealed interface ClientMessage {

    /** First frame after connect. See {@code SessionWebSocketHandler} for why. */
    record Hello(int protocolVersion, String accessToken) implements ClientMessage {}

    record Ask(Trigger trigger) implements ClientMessage {}

    record Screenshot(String mimeType, String dataBase64, String note) implements ClientMessage {}

    record Ping() implements ClientMessage {}

    enum Trigger {
        MANUAL,
        AUTO;

        @com.fasterxml.jackson.annotation.JsonValue
        public String wireName() {
            return name().toLowerCase();
        }
    }
}
