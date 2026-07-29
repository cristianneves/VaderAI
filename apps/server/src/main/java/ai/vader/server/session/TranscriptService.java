package ai.vader.server.session;

import ai.vader.server.persistence.SessionRepository;
import ai.vader.server.persistence.SessionRow;
import ai.vader.server.persistence.TranscriptTurn;
import ai.vader.server.persistence.TranscriptTurnRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The only place session and turn rows are read or written.
 *
 * <p>Every method takes the user id from the verified JWT and passes it down to
 * the repository. The backend connects with the service role, so RLS is not
 * enforcing this — these signatures are.
 */
@Service
public class TranscriptService {

    private final SessionRepository sessions;
    private final TranscriptTurnRepository turns;

    TranscriptService(SessionRepository sessions, TranscriptTurnRepository turns) {
        this.sessions = sessions;
        this.turns = turns;
    }

    public SessionRow openSession(UUID userId) {
        return sessions.save(SessionRow.opening(userId));
    }

    public void closeSession(UUID sessionId, UUID userId) {
        sessions.findByIdAndUserId(sessionId, userId).map(SessionRow::ended).ifPresent(sessions::save);
    }

    /** No-ops for a session the user does not own — that is the authorization check. */
    public void markPractice(UUID sessionId, UUID userId) {
        sessions.findByIdAndUserId(sessionId, userId).map(SessionRow::asPractice).ifPresent(sessions::save);
    }

    /** Batched by the caller — turns are never written one word at a time. */
    public void saveTurns(List<TranscriptTurn> batch) {
        if (!batch.isEmpty()) turns.saveAll(batch);
    }

    public List<TranscriptTurn> turnsOf(UUID sessionId, UUID userId) {
        return turns.findBySessionIdAndUserIdOrderByIdAsc(sessionId, userId);
    }

    public Optional<SessionRow> session(UUID sessionId, UUID userId) {
        return sessions.findByIdAndUserId(sessionId, userId);
    }
}
