package ai.vader.server.summary;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Same rule as everywhere else: the service role bypasses RLS, so reads take a userId. */
interface SessionSummaryRepository extends Repository<SessionSummary, UUID> {

    Optional<SessionSummary> findBySessionIdAndUserId(UUID sessionId, UUID userId);
}
