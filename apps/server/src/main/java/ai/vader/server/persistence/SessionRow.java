package ai.vader.server.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("sessions")
public record SessionRow(@Id UUID id, UUID userId, SessionKind kind, Instant startedAt, Instant endedAt) {

    /** A null id makes Spring Data JDBC insert and take the database default. */
    public static SessionRow opening(UUID userId) {
        return new SessionRow(null, userId, SessionKind.LIVE, Instant.now(), null);
    }

    public SessionRow ended() {
        return new SessionRow(id, userId, kind, startedAt, Instant.now());
    }

    /**
     * Practice mode opens an ordinary session and reclassifies it once the
     * question set exists — the socket is already connected by then, and it is
     * the practice request that knows what the session is for.
     */
    public SessionRow asPractice() {
        return new SessionRow(id, userId, SessionKind.PRACTICE, startedAt, endedAt);
    }
}
