package ai.vader.server.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A finalized turn. Interim results are relayed to the client but never stored —
 * they are rewritten several times a second and have no value once final.
 *
 * <p>{@code userId} is denormalized from the session so that every read can be
 * scoped without a join. The backend holds the service role and bypasses RLS,
 * so that scoping is the only thing standing between two users' data.
 */
@Table("transcript_turns")
public record TranscriptTurn(
        @Id Long id, UUID sessionId, UUID userId, short channel, String content, Instant createdAt) {

    public static TranscriptTurn of(UUID sessionId, UUID userId, int channel, String content) {
        return new TranscriptTurn(null, sessionId, userId, (short) channel, content, Instant.now());
    }
}
