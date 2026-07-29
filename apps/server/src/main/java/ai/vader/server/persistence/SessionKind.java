package ai.vader.server.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a session was for. Wire names match the {@code kind} check constraint on
 * {@code sessions}.
 */
public enum SessionKind {
    /** A real call: system audio on channel 0, mic on channel 1. */
    LIVE("live"),
    /** A mock interview: channel 0 is silent and the questions come from us. */
    PRACTICE("practice");

    private final String wireName;

    SessionKind(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static SessionKind fromWireName(String value) {
        for (SessionKind kind : values()) {
            if (kind.wireName.equals(value)) return kind;
        }
        throw new IllegalArgumentException("unknown session kind: " + value);
    }
}
