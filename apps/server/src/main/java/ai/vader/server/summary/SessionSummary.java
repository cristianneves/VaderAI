package ai.vader.server.summary;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A recap of one finished session, generated once and then read.
 *
 * <p>{@code sessionId} is the primary key rather than a surrogate: there is
 * exactly one recap per session, and letting the database say so is what makes
 * "generate only if absent" safe against two clients opening the panel at once.
 *
 * <p>{@code userId} is denormalized so reads can be scoped without a join, the
 * same rule the rest of the schema follows — the backend holds the service role
 * and bypasses RLS, so that scoping is the authorization.
 */
@Table("session_summaries")
record SessionSummary(
        @Id UUID sessionId,
        UUID userId,
        String summary,
        String[] keyPoints,
        String[] actionItems,
        Instant createdAt) {

    static SessionSummary of(
            UUID sessionId, UUID userId, String summary, String[] keyPoints, String[] actionItems) {
        return new SessionSummary(sessionId, userId, summary, keyPoints, actionItems, Instant.now());
    }
}
