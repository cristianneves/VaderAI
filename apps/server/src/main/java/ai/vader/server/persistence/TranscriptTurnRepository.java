package ai.vader.server.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface TranscriptTurnRepository extends CrudRepository<TranscriptTurn, Long> {

    List<TranscriptTurn> findBySessionIdAndUserIdOrderByIdAsc(UUID sessionId, UUID userId);

    long countBySessionIdAndUserId(UUID sessionId, UUID userId);
}
