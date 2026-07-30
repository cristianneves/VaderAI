package ai.vader.server.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Every finder takes the user id. The service role bypasses RLS, so a query
 * without it would happily return another user's rows.
 */
public interface AnswerRepository extends CrudRepository<Answer, Long> {

    List<Answer> findBySessionIdAndUserIdOrderByIdAsc(UUID sessionId, UUID userId);

    long countBySessionIdAndUserId(UUID sessionId, UUID userId);
}
