package ai.vader.server.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.UUID;

/**
 * Server → client messages. Mirrors the zod schemas in {@code packages/protocol}
 * and the fixtures in {@code contracts/messages/server.json}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ServerMessage.Ready.class, name = "ready"),
    @JsonSubTypes.Type(value = ServerMessage.Transcript.class, name = "transcript"),
    @JsonSubTypes.Type(value = ServerMessage.AnswerStart.class, name = "answer_start"),
    @JsonSubTypes.Type(value = ServerMessage.AnswerDelta.class, name = "answer_delta"),
    @JsonSubTypes.Type(value = ServerMessage.AnswerEnd.class, name = "answer_end"),
    @JsonSubTypes.Type(value = ServerMessage.Failure.class, name = "error"),
    @JsonSubTypes.Type(value = ServerMessage.Pong.class, name = "pong")
})
public sealed interface ServerMessage {

    record Ready(UUID sessionId) implements ServerMessage {}

    /** {@code channel} is 0 for the interviewer and 1 for the user. */
    record Transcript(int channel, String text, boolean isFinal) implements ServerMessage {}

    record AnswerStart(UUID answerId) implements ServerMessage {}

    record AnswerDelta(UUID answerId, String text) implements ServerMessage {}

    record AnswerEnd(UUID answerId) implements ServerMessage {}

    /** Wire type is {@code error}; named Failure so it does not shadow java.lang.Error. */
    record Failure(ErrorCode code, String message) implements ServerMessage {}

    record Pong() implements ServerMessage {}

    enum ErrorCode {
        UNAUTHORIZED,
        STT_FAILED,
        LLM_FAILED,
        BAD_REQUEST,
        RATE_LIMITED,
        INTERNAL;

        @JsonValue
        public String wireName() {
            return name().toLowerCase();
        }
    }
}
