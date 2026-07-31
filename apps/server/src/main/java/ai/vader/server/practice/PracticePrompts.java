package ai.vader.server.practice;

import ai.vader.server.preferences.Language;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prompts and response schemas for practice mode.
 *
 * <p>All three tasks — generating questions, grading an answer, summarising the
 * session — share one cached prefix: the system prompt below plus the user's
 * background. The task-specific instruction goes in the volatile user turn
 * instead of the system blocks, so the seven calls a five-question run makes
 * (one generate, five grades, one report) all hit the same cache entry.
 *
 * <p>Nothing volatile may leak into {@link #cachedBlocks} — a single changed byte
 * there invalidates the cache and the only symptom is a silently zero cache-read
 * count.
 */
final class PracticePrompts {

    private PracticePrompts() {}

    static final String SYSTEM_PROMPT =
            """
            You are an interview coach running a mock interview. You write the \
            questions, then grade the answers the candidate gives out loud.

            The candidate's background and the job they are applying for are below. \
            Everything you write is for them, addressed to them directly.

            Grade honestly. A vague answer that names no project, no number, and no \
            outcome is a weak answer however confidently it was delivered, and \
            saying so is the only thing that makes this worth doing. Do not soften \
            a low score with praise it did not earn.

            Answers reach you as speech-to-text of someone talking, so expect \
            filler words, false starts, and the occasional mistranscription. Grade \
            the substance, never the transcription — do not deduct for grammar, \
            punctuation, or a word that was obviously misheard.\
            """;

    /**
     * Stable system blocks. The last one carries the cache breakpoint downstream.
     *
     * <p>The language belongs here rather than in the per-task instruction: it is
     * constant for a session, so it caches with the rest of the prefix instead of
     * being re-sent on all seven calls.
     */
    static List<String> cachedBlocks(String knowledgeBase, Language language) {
        List<String> blocks = new ArrayList<>();
        blocks.add(SYSTEM_PROMPT + languageInstruction(language));
        if (knowledgeBase != null && !knowledgeBase.isBlank()) {
            blocks.add("Background on the candidate, and the role:\n\n" + knowledgeBase.strip());
        }
        return List.copyOf(blocks);
    }

    /**
     * Grading someone's Portuguese answer and handing back English feedback is
     * the obvious way to get this half-right, so questions, feedback and
     * rewrites are all pinned to the same language the session is transcribed in.
     */
    private static String languageInstruction(Language language) {
        return language == Language.MULTI
                ? "\n\nWrite the questions, the feedback and the rewrites in the language the candidate is speaking."
                : "\n\nWrite the questions, the feedback and the rewrites in " + language.englishName() + ".";
    }

    static String questionSetPrompt(int count) {
        return """
                Write %d interview questions for this role, in the order you would \
                ask them.

                Draw them from the job description: ask about the responsibilities \
                and skills it actually names, not generic ones. Mix behavioural \
                questions with at least one that probes the technical depth the role \
                calls for. Open with something a real interviewer would open with, \
                and make each question one a person can answer out loud in a minute \
                or two.

                Ask each question once — no compound questions stapled together with \
                "and also".\
                """
                .formatted(count);
    }

    static String gradePrompt(String question, String answer) {
        return """
                Grade this answer.

                Question: %s

                Their answer: %s

                Score structure, specificity, and relevance from 1 to 5 each. In the \
                feedback, name the single most useful thing to fix — quote the words \
                that were vague if that is the problem. Then rewrite the answer as \
                they should have said it: their voice, first person, grounded in the \
                background above, and short enough to say out loud. Invent no \
                experience they do not have; if the background is thin, keep the \
                rewrite honest and show them how to frame what is actually there.\
                """
                .formatted(question, answer);
    }

    static String reportPrompt(List<PracticeQuestion> graded) {
        var text = new StringBuilder("""
                Here is the whole session, question by question, with the scores you \
                already gave.

                """);
        for (PracticeQuestion question : graded) {
            text.append("Q: ")
                    .append(question.question())
                    .append("\nA: ")
                    .append(question.answer())
                    .append("\nScores — structure ")
                    .append(question.structure())
                    .append(", specificity ")
                    .append(question.specificity())
                    .append(", relevance ")
                    .append(question.relevance())
                    .append("\n\n");
        }
        text.append("""
                Name the two or three themes that cost them the most across the whole \
                session — patterns that showed up more than once, not a restatement of \
                one question's feedback. Then write a short summary they can act on \
                before the real interview: what to practise, and what they already do \
                well enough to leave alone.\
                """);
        return text.toString();
    }

    // --- Response schemas ---------------------------------------------------
    //
    // Scores use `enum` rather than `minimum`/`maximum`: structured outputs do
    // not support numeric constraints, and an unsupported keyword would be
    // dropped rather than enforced. `additionalProperties: false` is required on
    // every object.

    static final Map<String, Object> QUESTION_SET_SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of("questions", Map.of("type", "array", "items", Map.of("type", "string"))),
            "required",
            List.of("questions"),
            "additionalProperties",
            false);

    static final Map<String, Object> GRADE_SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "structure", score(),
                    "specificity", score(),
                    "relevance", score(),
                    "feedback", Map.of("type", "string"),
                    "rewrite", Map.of("type", "string")),
            "required",
            List.of("structure", "specificity", "relevance", "feedback", "rewrite"),
            "additionalProperties",
            false);

    static final Map<String, Object> REPORT_SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                    "themes", Map.of("type", "array", "items", Map.of("type", "string")),
                    "summary", Map.of("type", "string")),
            "required",
            List.of("themes", "summary"),
            "additionalProperties",
            false);

    private static Map<String, Object> score() {
        return Map.of("type", "integer", "enum", List.of(1, 2, 3, 4, 5));
    }

    /** Shape of {@link #QUESTION_SET_SCHEMA}. */
    record QuestionSet(List<String> questions) {}

    /** Shape of {@link #REPORT_SCHEMA}. */
    record ReportSummary(List<String> themes, String summary) {}
}
