package ai.vader.server.practice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Every finder takes the user id. The service role bypasses RLS, so a query
 * without it would happily return another user's rows.
 */
interface PracticeQuestionRepository extends CrudRepository<PracticeQuestion, Long> {

    List<PracticeQuestion> findBySessionIdAndUserIdOrderByPositionAsc(UUID sessionId, UUID userId);

    Optional<PracticeQuestion> findBySessionIdAndUserIdAndPosition(UUID sessionId, UUID userId, short position);
}
