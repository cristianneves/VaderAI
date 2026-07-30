package ai.vader.server.summary;

import ai.vader.server.llm.JsonEngine;
import ai.vader.server.persistence.Answer;
import ai.vader.server.persistence.TranscriptTurn;
import ai.vader.server.preferences.PreferencesService;
import ai.vader.server.session.TranscriptService;
import ai.vader.server.stt.TranscriptEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generates a post-call recap once, then serves it from storage.
 *
 * <p>The "once" is the point. The practice report route regenerates its themes
 * on every view, which bills a model call to reopen a page; a recap is read far
 * more often than it is produced, so it is written down.
 */
@Service
public class SummaryService {

    private final SessionSummaryRepository summaries;
    private final JdbcAggregateTemplate template;
    private final TranscriptService transcripts;
    private final PreferencesService preferences;
    private final JsonEngine llm;
    private final ObjectMapper json;

    SummaryService(
            SessionSummaryRepository summaries,
            JdbcAggregateTemplate template,
            TranscriptService transcripts,
            PreferencesService preferences,
            JsonEngine llm,
            ObjectMapper json) {
        this.summaries = summaries;
        this.template = template;
        this.transcripts = transcripts;
        this.preferences = preferences;
        this.llm = llm;
        this.json = json;
    }

    public record Recap(String summary, List<String> keyPoints, List<String> actionItems) {}

    /** Thrown when a session has nothing in it worth summarising. */
    public static class EmptySessionException extends RuntimeException {
        EmptySessionException() {
            super("Nothing was said in this session — there is nothing to summarise.");
        }
    }

    /**
     * Returns the stored recap, generating it on first call.
     *
     * @throws ResponseStatusException 404 if the session is not this user's
     */
    public Recap recapOf(UUID sessionId, UUID userId) {
        var stored = summaries.findBySessionIdAndUserId(sessionId, userId);
        if (stored.isPresent()) return view(stored.get());

        transcripts
                .session(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no session " + sessionId));

        String conversation = render(
                transcripts.turnsOf(sessionId, userId), transcripts.answersOf(sessionId, userId));
        if (conversation.isBlank()) throw new EmptySessionException();

        SummaryPrompts.Recap generated = generate(conversation, userId);
        return view(store(sessionId, userId, generated));
    }

    private SummaryPrompts.Recap generate(String conversation, UUID userId) {
        String body = llm.complete(
                SummaryPrompts.cachedBlocks(preferences.language(userId)),
                SummaryPrompts.recapPrompt(conversation),
                SummaryPrompts.SCHEMA);
        try {
            return json.readValue(body, SummaryPrompts.Recap.class);
        } catch (Exception malformed) {
            // The schema is enforced by the API, so this means the response was
            // truncated or the shape drifted — not the user's fault either way.
            throw new IllegalStateException("could not read the recap the model returned", malformed);
        }
    }

    /**
     * Insert rather than save: the id is the session id and is therefore never
     * null, so {@code save} would issue an UPDATE against a row that does not
     * exist yet. Two clients opening the panel at once both generate, and the
     * primary key decides which write lands — the loser reads the winner's.
     */
    private SessionSummary store(UUID sessionId, UUID userId, SummaryPrompts.Recap recap) {
        var row = SessionSummary.of(
                sessionId,
                userId,
                recap.summary(),
                recap.keyPoints().toArray(String[]::new),
                recap.actionItems().toArray(String[]::new));
        try {
            return template.insert(row);
        } catch (DuplicateKeyException raced) {
            return summaries.findBySessionIdAndUserId(sessionId, userId).orElseThrow();
        }
    }

    private static Recap view(SessionSummary row) {
        return new Recap(row.summary(), List.of(row.keyPoints()), List.of(row.actionItems()));
    }

    /**
     * Turns and answers interleaved by timestamp, the same order the review
     * screen shows them in. The assistant's answers are included and labelled:
     * they are usually what the user actually said, and a recap built from the
     * interviewer's half alone would be missing one side of the conversation.
     */
    static String render(List<TranscriptTurn> turns, List<Answer> answers) {
        record Line(java.time.Instant at, String text) {}

        return java.util.stream.Stream.concat(
                        turns.stream()
                                .map(turn -> new Line(
                                        turn.createdAt(),
                                        (turn.channel() == TranscriptEvent.CHANNEL_INTERVIEWER
                                                        ? "Interviewer: "
                                                        : "You: ")
                                                + turn.content())),
                        answers.stream().map(answer -> new Line(answer.createdAt(), "Suggested: " + answer.content())))
                .sorted(Comparator.comparing(Line::at))
                .map(Line::text)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
