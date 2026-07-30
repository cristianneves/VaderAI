package ai.vader.server.summary;

import static org.assertj.core.api.Assertions.assertThat;

import ai.vader.server.persistence.Answer;
import ai.vader.server.persistence.AnswerTrigger;
import ai.vader.server.persistence.TranscriptTurn;
import ai.vader.server.preferences.Language;
import ai.vader.server.stt.TranscriptEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The parts of the recap that do not need a database: how the conversation is
 * rendered for the model, and what goes in the cached prefix.
 */
class SummaryServiceTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-07-30T10:00:00Z");

    private static TranscriptTurn turn(int secondsIn, int channel, String content) {
        return new TranscriptTurn(null, SESSION, USER, (short) channel, content, T0.plusSeconds(secondsIn));
    }

    private static Answer answer(int secondsIn, String content) {
        return new Answer(null, SESSION, USER, content, AnswerTrigger.AUTO, T0.plusSeconds(secondsIn));
    }

    @Test
    void interleavesTurnsAndAnswersByTimestamp() {
        String rendered = SummaryService.render(
                List.of(
                        turn(0, TranscriptEvent.CHANNEL_INTERVIEWER, "What did you ship?"),
                        turn(4, TranscriptEvent.CHANNEL_USER, "A payments rewrite.")),
                List.of(answer(2, "Lead with the payments rewrite.")));

        assertThat(rendered)
                .isEqualTo(
                        """
                        Interviewer: What did you ship?
                        Suggested: Lead with the payments rewrite.
                        You: A payments rewrite.""");
    }

    @Test
    void labelsEachSpeakerDistinctly() {
        String rendered = SummaryService.render(
                List.of(
                        turn(0, TranscriptEvent.CHANNEL_INTERVIEWER, "one"),
                        turn(1, TranscriptEvent.CHANNEL_USER, "two")),
                List.of(answer(2, "three")));

        assertThat(rendered).contains("Interviewer: one").contains("You: two").contains("Suggested: three");
    }

    @Test
    void anEmptySessionRendersEmptySoTheCallerCanRefuseToBillForIt() {
        assertThat(SummaryService.render(List.of(), List.of())).isEmpty();
    }

    @Test
    void aSessionWithOnlyAnswersStillRenders() {
        // A screenshot question with no spoken audio around it.
        assertThat(SummaryService.render(List.of(), List.of(answer(0, "Use a heap."))))
                .isEqualTo("Suggested: Use a heap.");
    }

    @Test
    void theRecapPromptCarriesTheConversationAndIsNotCached() {
        String prompt = SummaryPrompts.recapPrompt("Interviewer: hello");

        assertThat(prompt).contains("Interviewer: hello");
        assertThat(SummaryPrompts.cachedBlocks(Language.ENGLISH).toString()).doesNotContain("Interviewer: hello");
    }

    @Test
    void theRecapComesBackInTheSessionLanguage() {
        assertThat(SummaryPrompts.cachedBlocks(Language.PORTUGUESE).get(0)).endsWith("in Brazilian Portuguese.");
        assertThat(SummaryPrompts.cachedBlocks(Language.MULTI).get(0))
                .endsWith("in the language the conversation was held in.");
    }

    @Test
    void theSchemaRequiresEveryFieldAndClosesItself() {
        // Structured outputs quietly omit fields that are not required, and
        // reject an object schema without additionalProperties: false.
        assertThat(SummaryPrompts.SCHEMA.get("additionalProperties")).isEqualTo(false);
        assertThat(SummaryPrompts.SCHEMA.get("required")).isEqualTo(List.of("summary", "keyPoints", "actionItems"));
    }
}
