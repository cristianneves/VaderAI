package ai.vader.server.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("sessions")
public record SessionRow(@Id UUID id, UUID userId, Instant startedAt, Instant endedAt) {

    /** A null id makes Spring Data JDBC insert and take the database default. */
    public static SessionRow opening(UUID userId) {
        return new SessionRow(null, userId, Instant.now(), null);
    }

    public SessionRow ended() {
        return new SessionRow(id, userId, startedAt, Instant.now());
    }
}
