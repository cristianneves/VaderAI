package ai.vader.server.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PracticePromptsTest {

    private static final String BACKGROUND = "Job description:\nstaff engineer, payments";

    private static PracticeQuestion graded(int position, String question, String answer, int structure) {
        return PracticeQuestion.asked(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), position, question)
                .graded(answer, new PracticeGrade(structure, 2, 4, "too vague", "better answer"));
    }

    @Test
    void putsTheSystemPromptAndBackgroundInTheCachedPrefix() {
        List<String> blocks = PracticePrompts.cachedBlocks(BACKGROUND);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).isEqualTo(PracticePrompts.SYSTEM_PROMPT);
        assertThat(blocks.get(1)).contains("staff engineer, payments");
    }

    @Test
    void omitsTheBackgroundBlockWhenThereIsNone() {
        assertThat(PracticePrompts.cachedBlocks("")).hasSize(1);
        assertThat(PracticePrompts.cachedBlocks("   ")).hasSize(1);
        assertThat(PracticePrompts.cachedBlocks(null)).hasSize(1);
    }

    @Test
    void isByteStableAcrossCalls() {
        // The prefix is a cache key in all but name. One changed byte here means a
        // cold cache for every call that follows, with no symptom but a zero
        // cache-read count.
        assertThat(PracticePrompts.cachedBlocks(BACKGROUND)).isEqualTo(PracticePrompts.cachedBlocks(BACKGROUND));
    }

    @Test
    void keepsEveryTaskOnTheSameCachedPrefix() {
        // Generating, grading, and summarising all share one prefix, so the five
        // grades in a run are cache reads rather than five cold writes.
        List<String> blocks = PracticePrompts.cachedBlocks(BACKGROUND);

        assertThat(blocks).isEqualTo(PracticePrompts.cachedBlocks(BACKGROUND));
        assertThat(blocks.toString()).doesNotContain("Grade this answer");
        assertThat(blocks.toString()).doesNotContain("interview questions for this role");
    }

    @Test
    void asksForTheRequestedNumberOfQuestions() {
        assertThat(PracticePrompts.questionSetPrompt(5)).contains("5 interview questions");
    }

    @Test
    void gradingPromptCarriesTheQuestionAndAnswer() {
        String prompt = PracticePrompts.gradePrompt("Tell me about a hard bug.", "It was fine I guess.");

        assertThat(prompt).contains("Tell me about a hard bug.");
        assertThat(prompt).contains("It was fine I guess.");
        assertThat(prompt).contains("structure, specificity, and relevance");
        assertThat(prompt).contains("rewrite");
    }

    @Test
    void reportPromptCarriesEveryGradedAnswerAndItsScores() {
        String prompt = PracticePrompts.reportPrompt(
                List.of(graded(0, "First question?", "First answer.", 1), graded(1, "Second question?", "Second.", 5)));

        assertThat(prompt).contains("First question?").contains("First answer.");
        assertThat(prompt).contains("Second question?").contains("Second.");
        assertThat(prompt).contains("structure 1").contains("structure 5");
        assertThat(prompt).contains("two or three themes");
    }

    @Test
    void scoreSchemaConstrainsTheRangeWithAnEnum() {
        // Structured outputs ignore `minimum`/`maximum`, so an enum is the only
        // way to actually hold scores to 1-5.
        @SuppressWarnings("unchecked")
        var structure =
                (java.util.Map<String, Object>) ((java.util.Map<String, Object>) PracticePrompts.GRADE_SCHEMA.get(
                                "properties"))
                        .get("structure");

        assertThat(structure.get("type")).isEqualTo("integer");
        assertThat(structure.get("enum")).isEqualTo(List.of(1, 2, 3, 4, 5));
    }

    @Test
    void everySchemaClosesItselfToExtraProperties() {
        // Required by structured outputs; a missing one is rejected at request time.
        assertThat(PracticePrompts.QUESTION_SET_SCHEMA.get("additionalProperties")).isEqualTo(false);
        assertThat(PracticePrompts.GRADE_SCHEMA.get("additionalProperties")).isEqualTo(false);
        assertThat(PracticePrompts.REPORT_SCHEMA.get("additionalProperties")).isEqualTo(false);
    }
}
