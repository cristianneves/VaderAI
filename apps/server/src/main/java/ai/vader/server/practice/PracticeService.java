package ai.vader.server.practice;

import ai.vader.server.knowledge.KnowledgeKind;
import ai.vader.server.knowledge.KnowledgeService;
import ai.vader.server.llm.JsonEngine;
import ai.vader.server.preferences.PreferencesService;
import ai.vader.server.session.TranscriptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Runs a mock interview: generates the question set, grades each spoken answer,
 * and summarises the session.
 *
 * <p>Practice mode borrows the live pipeline wholesale. The client opens the
 * ordinary session socket, so audio, speech-to-text, and transcript persistence
 * all work unchanged; the only difference is that channel 0 is silent, which
 * means the auto-ask path never arms and no live answers are generated. This
 * service only deals in text the client has already collected from channel 1.
 *
 * <p>Every method takes the user id from the verified JWT and passes it to the
 * repository. The backend holds the service role and bypasses RLS, so these
 * signatures are the authorization.
 */
@Service
public class PracticeService {

    /** What the exit criterion asks for, and about as long as anyone sits through. */
    static final int QUESTION_COUNT = 5;

    private final PracticeQuestionRepository questions;
    private final TranscriptService transcripts;
    private final KnowledgeService knowledge;
    private final PreferencesService preferences;
    private final JsonEngine llm;
    private final ObjectMapper json;

    PracticeService(
            PracticeQuestionRepository questions,
            TranscriptService transcripts,
            KnowledgeService knowledge,
            PreferencesService preferences,
            JsonEngine llm,
            ObjectMapper json) {
        this.questions = questions;
        this.transcripts = transcripts;
        this.knowledge = knowledge;
        this.preferences = preferences;
        this.llm = llm;
        this.json = json;
    }

    /** Thrown when there is nothing to build a question set from. */
    public static class MissingJobDescriptionException extends RuntimeException {
        MissingJobDescriptionException() {
            super("Add a job description in Settings before starting a mock interview.");
        }
    }

    /** Thrown when the session id is not this user's, or has no question set yet. */
    public static class UnknownPracticeSessionException extends RuntimeException {
        UnknownPracticeSessionException(String message) {
            super(message);
        }
    }

    /**
     * Generates and stores the question set, and reclassifies the session as
     * practice. Idempotent: a retry returns the existing questions rather than a
     * second set, which the unique index on (session_id, position) also enforces.
     */
    public List<PracticeQuestion> start(UUID userId, UUID sessionId) {
        requireSession(userId, sessionId);

        List<PracticeQuestion> existing = questions.findBySessionIdAndUserIdOrderByPositionAsc(sessionId, userId);
        if (!existing.isEmpty()) return existing;

        if (knowledge.content(userId, KnowledgeKind.JOB_DESCRIPTION).isEmpty()) {
            throw new MissingJobDescriptionException();
        }

        var generated = complete(
                userId,
                PracticePrompts.questionSetPrompt(QUESTION_COUNT),
                PracticePrompts.QUESTION_SET_SCHEMA,
                PracticePrompts.QuestionSet.class);

        List<PracticeQuestion> rows = new ArrayList<>();
        List<String> asked = generated.questions();
        for (int i = 0; i < Math.min(asked.size(), QUESTION_COUNT); i++) {
            rows.add(PracticeQuestion.asked(sessionId, userId, i, asked.get(i)));
        }
        if (rows.isEmpty()) throw new IllegalStateException("the model returned no questions");

        transcripts.markPractice(sessionId, userId);
        questions.saveAll(rows);
        // Read back rather than trusting saveAll's iteration order — position
        // order is what the client presents them in.
        return questions.findBySessionIdAndUserIdOrderByPositionAsc(sessionId, userId);
    }

    /** Grades one answer and stores the result. Re-grading a position overwrites it. */
    public PracticeQuestion grade(UUID userId, UUID sessionId, int position, String answer) {
        PracticeQuestion question = questions
                .findBySessionIdAndUserIdAndPosition(sessionId, userId, (short) position)
                .orElseThrow(() -> new UnknownPracticeSessionException("no question at position " + position));

        var grade = complete(
                userId,
                PracticePrompts.gradePrompt(question.question(), answer),
                PracticePrompts.GRADE_SCHEMA,
                PracticeGrade.class);

        return questions.save(question.graded(answer, grade));
    }

    /**
     * The whole session plus the themes that cost the most across it.
     *
     * <p>The themes are regenerated on each call rather than stored. A mock
     * interview is reviewed once or twice, so one small call per view is cheaper
     * than a column that has to be kept in step with re-grades.
     */
    public Report report(UUID userId, UUID sessionId) {
        List<PracticeQuestion> all = questions.findBySessionIdAndUserIdOrderByPositionAsc(sessionId, userId);
        if (all.isEmpty()) throw new UnknownPracticeSessionException("no practice session " + sessionId);

        List<PracticeQuestion> graded = all.stream().filter(PracticeQuestion::isGraded).toList();
        if (graded.isEmpty()) return new Report(all, List.of(), "");

        var summary = complete(
                userId,
                PracticePrompts.reportPrompt(graded),
                PracticePrompts.REPORT_SCHEMA,
                PracticePrompts.ReportSummary.class);

        return new Report(all, summary.themes(), summary.summary());
    }

    public List<PracticeQuestion> questionsOf(UUID userId, UUID sessionId) {
        return questions.findBySessionIdAndUserIdOrderByPositionAsc(sessionId, userId);
    }

    public record Report(List<PracticeQuestion> questions, List<String> themes, String summary) {}

    private void requireSession(UUID userId, UUID sessionId) {
        if (transcripts.session(sessionId, userId).isEmpty()) {
            throw new UnknownPracticeSessionException("no session " + sessionId);
        }
    }

    private <T> T complete(UUID userId, String prompt, Map<String, Object> schema, Class<T> shape) {
        // Read once per call rather than cached: the background is only a few
        // hundred tokens and a stale copy would silently grade against an old
        // job description.
        List<String> blocks = PracticePrompts.cachedBlocks(knowledge.assemble(userId), preferences.language(userId));
        String body = llm.complete(blocks, prompt, schema);
        try {
            return json.readValue(body, shape);
        } catch (Exception malformed) {
            // The schema is enforced by the API, so this means the response was
            // truncated or the shape drifted — either way it is not the user's
            // problem to debug.
            throw new IllegalStateException("could not read the model's " + shape.getSimpleName(), malformed);
        }
    }
}
