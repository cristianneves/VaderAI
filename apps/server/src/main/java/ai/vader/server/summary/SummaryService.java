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

    private final SessionSummaryStore summaries;
    private final TranscriptService transcripts;
    private final PreferencesService preferences;
    private final JsonEngine llm;
    private final ObjectMapper json;

    SummaryService(
            SessionSummaryStore summaries,
            TranscriptService transcripts,
            PreferencesService preferences,
            JsonEngine llm,
            ObjectMapper json) {
        this.summaries = summaries;
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
        var stored = summaries.find(sessionId, userId);
        if (stored.isPresent()) return stored.get();

        transcripts
                .session(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no session " + sessionId));

        String conversation = render(
                transcripts.turnsOf(sessionId, userId), transcripts.answersOf(sessionId, userId));
        if (conversation.isBlank()) throw new EmptySessionException();

        SummaryPrompts.Recap generated = generate(conversation, userId);
        var recap = new Recap(generated.summary(), generated.keyPoints(), generated.actionItems());
        summaries.insert(sessionId, userId, recap);
        // Read back rather than return what we just built: on a race the row
        // that landed is the other client's, and both should see the same text.
        return summaries.find(sessionId, userId).orElse(recap);
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
