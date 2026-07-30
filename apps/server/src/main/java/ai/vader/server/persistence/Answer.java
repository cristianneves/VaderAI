package ai.vader.server.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An answer that was actually given, written once when its stream completes.
 *
 * <p>A cancelled answer never lands here. Cancelling is deliberate — a new
 * question makes the previous one stale — so the half sentence the user saw
 * before it was replaced is not worth keeping.
 *
 * <p>{@code userId} is denormalized from the session so every read can be scoped
 * without a join. The backend holds the service role and bypasses RLS, so that
 * scoping is the only thing standing between two users' data.
 */
@Table("answers")
public record Answer(
        @Id Long id,
        UUID sessionId,
        UUID userId,
        String content,
        AnswerTrigger trigger,
        Instant createdAt) {

    public static Answer of(UUID sessionId, UUID userId, String content, AnswerTrigger trigger) {
        return new Answer(null, sessionId, userId, content, trigger, Instant.now());
    }
}
