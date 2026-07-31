package ai.vader.server.session;

import ai.vader.server.persistence.Answer;
import ai.vader.server.persistence.AnswerTrigger;
import ai.vader.server.persistence.SessionKind;
import ai.vader.server.persistence.SessionRow;
import ai.vader.server.persistence.TranscriptTurn;
import ai.vader.server.summary.SummaryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Session history. Every method takes the user id from the verified JWT — never
 * from the path — so one user cannot read or delete another's sessions.
 *
 * <p>Note the plural: {@code /v1/session} (singular) is the WebSocket endpoint and
 * is {@code permitAll}, because it authenticates itself with its first frame
 * instead. These routes are one character away from that and are **not** public —
 * Spring matches the permitAll rule on the exact path, and
 * {@code VaderAiApplicationTests} pins that rather than leaving it to inference.
 *
 * <p>Practice sessions are not special-cased here. The client fetches
 * {@code GET /v1/practice/{sessionId}} for those, which already returns the
 * persisted questions and grades and — unlike the report route — costs no model
 * call, so reopening an old mock interview is free.
 */
@RestController
@RequestMapping("/v1/sessions")
class SessionHistoryController {

    private final TranscriptService transcripts;
    private final SummaryService summaries;

    SessionHistoryController(TranscriptService transcripts, SummaryService summaries) {
        this.transcripts = transcripts;
        this.summaries = summaries;
    }

    /**
     * @param endedAt null for a session whose socket died before it was closed;
     *     the client renders that as unfinished rather than guessing a duration
     */
    record SummaryView(
            UUID id,
            SessionKind kind,
            Instant startedAt,
            Instant endedAt,
            long turns,
            long answers,
            long practiceQuestions) {

        static SummaryView of(TranscriptService.Summary summary) {
            return new SummaryView(
                    summary.id(),
                    summary.kind(),
                    summary.startedAt(),
                    summary.endedAt(),
                    summary.turns(),
                    summary.answers(),
                    summary.practiceQuestions());
        }
    }

    record TurnView(int channel, String content, Instant createdAt) {
        static TurnView of(TranscriptTurn turn) {
            return new TurnView(turn.channel(), turn.content(), turn.createdAt());
        }
    }

    record AnswerView(String content, AnswerTrigger trigger, Instant createdAt) {
        static AnswerView of(Answer answer) {
            return new AnswerView(answer.content(), answer.trigger(), answer.createdAt());
        }
    }

    /** Turns and answers arrive separately; the client interleaves them by timestamp. */
    record SessionView(
            UUID id,
            SessionKind kind,
            Instant startedAt,
            Instant endedAt,
            List<TurnView> turns,
            List<AnswerView> answers) {}

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping
    List<SummaryView> list(@AuthenticationPrincipal Jwt jwt) {
        return transcripts.summaries(userIdOf(jwt)).stream()
                .map(SummaryView::of)
                .toList();
    }

    @GetMapping("/{sessionId}")
    SessionView detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        UUID userId = userIdOf(jwt);
        SessionRow row = transcripts
                .session(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no session " + sessionId));

        return new SessionView(
                row.id(),
                row.kind(),
                row.startedAt(),
                row.endedAt(),
                transcripts.turnsOf(sessionId, userId).stream()
                        .map(TurnView::of)
                        .toList(),
                transcripts.answersOf(sessionId, userId).stream()
                        .map(AnswerView::of)
                        .toList());
    }

    /**
     * The recap. Generated on the first call and stored, so reopening it later
     * is a select rather than another model call.
     */
    @GetMapping("/{sessionId}/summary")
    SummaryService.Recap summary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return summaries.recapOf(sessionId, userIdOf(jwt));
    }

    @ExceptionHandler(SummaryService.EmptySessionException.class)
    ResponseEntity<String> onEmptySession(SummaryService.EmptySessionException failed) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(failed.getMessage());
    }

    /** The database cascade removes the turns, answers, and practice questions. */
    @DeleteMapping("/{sessionId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        if (!transcripts.deleteSession(sessionId, userIdOf(jwt))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no session " + sessionId);
        }
        return ResponseEntity.noContent().build();
    }
}
