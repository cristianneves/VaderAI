package ai.vader.server.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Every finder takes a userId. That is the convention this codebase relies on:
 * the service role bypasses RLS, so an unscoped query would happily return
 * another user's rows.
 */
public interface SessionRepository extends CrudRepository<SessionRow, UUID> {

    Optional<SessionRow> findByIdAndUserId(UUID id, UUID userId);

    List<SessionRow> findByUserIdOrderByStartedAtDesc(UUID userId);
}
