package ai.vader.server.session;

import ai.vader.server.persistence.Answer;
import ai.vader.server.persistence.AnswerRepository;
import ai.vader.server.persistence.SessionKind;
import ai.vader.server.persistence.SessionRepository;
import ai.vader.server.persistence.SessionRow;
import ai.vader.server.persistence.TranscriptTurn;
import ai.vader.server.persistence.TranscriptTurnRepository;
import ai.vader.server.practice.PracticeQuestionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The only place session, turn, and answer rows are read or written.
 *
 * <p>Every method takes the user id from the verified JWT and passes it down to
 * the repository. The backend connects with the service role, so RLS is not
 * enforcing this — these signatures are.
 */
@Service
public class TranscriptService {

    private final SessionRepository sessions;
    private final TranscriptTurnRepository turns;
    private final AnswerRepository answers;

    /**
     * Reached directly rather than through {@code PracticeService}, which depends
     * on this class — going the other way would be a bean cycle. A practice run
     * stores its answers on its question rows, not in {@code answers}, so without
     * this count the history list would show a five-question mock interview as
     * having none.
     */
    private final PracticeQuestionRepository practiceQuestions;

    TranscriptService(
            SessionRepository sessions,
            TranscriptTurnRepository turns,
            AnswerRepository answers,
            PracticeQuestionRepository practiceQuestions) {
        this.sessions = sessions;
        this.turns = turns;
        this.answers = answers;
        this.practiceQuestions = practiceQuestions;
    }

    /**
     * One row in the history list. Duration is deliberately not computed here:
     * {@code endedAt} is null for a session whose socket died before
     * {@link #closeSession} ran, and only the client knows how to say "unfinished".
     */
    public record Summary(
            UUID id,
            SessionKind kind,
            Instant startedAt,
            Instant endedAt,
            long turns,
            long answers,
            long practiceQuestions) {}

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

    /** One row per completed answer; unlike turns there is nothing to batch. */
    public void saveAnswer(Answer answer) {
        answers.save(answer);
    }

    public List<TranscriptTurn> turnsOf(UUID sessionId, UUID userId) {
        return turns.findBySessionIdAndUserIdOrderByIdAsc(sessionId, userId);
    }

    public List<Answer> answersOf(UUID sessionId, UUID userId) {
        return answers.findBySessionIdAndUserIdOrderByIdAsc(sessionId, userId);
    }

    public Optional<SessionRow> session(UUID sessionId, UUID userId) {
        return sessions.findByIdAndUserId(sessionId, userId);
    }

    /**
     * The history list, newest first.
     *
     * <p>Counts are three queries per session. That is deliberate: a personal
     * session list holds tens of rows, and one hand-written query with scalar
     * subqueries would be the only {@code @Query} in the codebase. If the list
     * ever grows enough to notice, that is the change to make.
     */
    public List<Summary> summaries(UUID userId) {
        return sessions.findByUserIdOrderByStartedAtDesc(userId).stream()
                .map(row -> new Summary(
                        row.id(),
                        row.kind(),
                        row.startedAt(),
                        row.endedAt(),
                        turns.countBySessionIdAndUserId(row.id(), userId),
                        answers.countBySessionIdAndUserId(row.id(), userId),
                        practiceQuestions.countBySessionIdAndUserId(row.id(), userId)))
                .toList();
    }

    /**
     * Deletes a session and, by cascade, its turns, answers, and practice
     * questions. Silently does nothing for a session the user does not own.
     *
     * @return whether anything was deleted, so the caller can 404
     */
    public boolean deleteSession(UUID sessionId, UUID userId) {
        return sessions.findByIdAndUserId(sessionId, userId)
                .map(row -> {
                    sessions.delete(row);
                    return true;
                })
                .orElse(false);
    }
}
