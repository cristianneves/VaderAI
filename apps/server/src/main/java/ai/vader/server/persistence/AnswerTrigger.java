package ai.vader.server.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What prompted an answer. Wire names match the {@code trigger} check constraint
 * on {@code answers}.
 */
public enum AnswerTrigger {
    /** The interviewer stopped talking and the silence window elapsed. */
    AUTO("auto"),
    /** {@code Ctrl+Enter} — asked regardless of what the transcript looks like. */
    MANUAL("manual"),
    /** {@code Ctrl+H} — about the screen, so there may be no spoken question at all. */
    SCREENSHOT("screenshot");

    private final String wireName;

    AnswerTrigger(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static AnswerTrigger fromWireName(String value) {
        for (AnswerTrigger trigger : values()) {
            if (trigger.wireName.equals(value)) return trigger;
        }
        throw new IllegalArgumentException("unknown answer trigger: " + value);
    }
}
