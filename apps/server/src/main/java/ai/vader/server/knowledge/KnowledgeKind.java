package ai.vader.server.knowledge;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The three slots the settings screen offers. Wire names match the {@code kind}
 * check constraint on {@code knowledge_docs}.
 *
 * <p>Declaration order is the order the knowledge base is assembled in, and it
 * has to stay fixed: the assembled text is the cached prompt prefix, so
 * reordering it would invalidate the cache for every user.
 */
public enum KnowledgeKind {
    RESUME("resume", "Résumé"),
    JOB_DESCRIPTION("job_description", "Job description"),
    NOTES("notes", "Notes");

    private final String wireName;
    private final String heading;

    KnowledgeKind(String wireName, String heading) {
        this.wireName = wireName;
        this.heading = heading;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** Used as the section heading inside the assembled knowledge base. */
    public String heading() {
        return heading;
    }

    @JsonCreator
    public static KnowledgeKind fromWireName(String value) {
        for (KnowledgeKind kind : values()) {
            if (kind.wireName.equals(value)) return kind;
        }
        throw new IllegalArgumentException("unknown knowledge kind: " + value);
    }
}
