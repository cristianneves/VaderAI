package ai.vader.server.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import ai.vader.server.llm.AnswerRequest;
import ai.vader.server.stt.TranscriptEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PromptAssemblerTest {

    private final PromptAssembler assembler = new PromptAssembler();

    private static final List<TranscriptEvent> TURNS = List.of(
            new TranscriptEvent(TranscriptEvent.CHANNEL_INTERVIEWER, "Tell me about a hard bug.", true),
            new TranscriptEvent(TranscriptEvent.CHANNEL_USER, "Sure, at Acme...", true));

    /** The common case: no typed question, nothing remembered yet. */
    private AnswerRequest assemble(List<TranscriptEvent> turns, String knowledge) {
        return assembler.assemble(turns, knowledge, Optional.empty(), null, List.of());
    }

    @Test
    void putsTheSystemPromptInTheCachedPrefix() {
        AnswerRequest request = assemble(TURNS, "");

        assertThat(request.cachedBlocks()).hasSize(1);
        assertThat(request.cachedBlocks().get(0)).isEqualTo(PromptAssembler.SYSTEM_PROMPT);
    }

    @Test
    void putsTheKnowledgeBaseInTheCachedPrefixToo() {
        AnswerRequest request = assemble(TURNS, "Cut deploy time from 40 to 6 minutes.");

        assertThat(request.cachedBlocks()).hasSize(2);
        assertThat(request.cachedBlocks().get(1)).contains("Cut deploy time from 40 to 6 minutes.");
    }

    @Test
    void keepsTheTranscriptOutOfTheCachedPrefix() {
        // This is the whole point of the split: a turn leaking into the cached
        // blocks would invalidate the cache on every single question.
        AnswerRequest request = assemble(TURNS, "background");

        assertThat(request.cachedBlocks()).noneMatch(block -> block.contains("hard bug"));
        assertThat(request.conversation()).contains("hard bug");
    }

    @Test
    void labelsEachSpeaker() {
        AnswerRequest request = assemble(TURNS, "");

        assertThat(request.conversation())
                .contains("Interviewer: Tell me about a hard bug.")
                .contains("You: Sure, at Acme...");
    }

    @Test
    void isStableAcrossCallsWithTheSameInput() {
        var first = assemble(TURNS, "background");
        var second = assemble(TURNS, "background");

        assertThat(first.cachedBlocks()).isEqualTo(second.cachedBlocks());
    }

    @Test
    void handlesAnEmptyTranscript() {
        AnswerRequest request = assemble(List.of(), "");

        assertThat(request.conversation()).contains("no transcript yet");
    }

    @Test
    void pointsTheQuestionAtTheScreenWhenAnImageIsAttached() {
        var image = new AnswerRequest.ImageInput("image/png", "AAAA");

        AnswerRequest request = assembler.assemble(TURNS, "", Optional.of(image), null, List.of());

        assertThat(request.image()).contains(image);
        assertThat(request.conversation()).contains("on the screen");
    }

    @Test
    void blankKnowledgeBaseAddsNoBlock() {
        assertThat(assemble(TURNS, "   ").cachedBlocks()).hasSize(1);
    }

    // --- typed questions ---------------------------------------------------

    @Test
    void carriesATypedQuestionInsteadOfPointingAtTheTranscript() {
        AnswerRequest request =
                assembler.assemble(TURNS, "", Optional.empty(), "Explain that more simply.", List.of());

        assertThat(request.conversation())
                .contains("Explain that more simply.")
                .doesNotContain("Answer the interviewer's most recent question.");
    }

    @Test
    void aTypedQuestionAboutAnImageStillPointsAtTheScreen() {
        var image = new AnswerRequest.ImageInput("image/png", "AAAA");

        AnswerRequest request =
                assembler.assemble(TURNS, "", Optional.of(image), "What is the complexity?", List.of());

        assertThat(request.conversation()).contains("on the screen").contains("What is the complexity?");
    }

    @Test
    void aBlankQuestionFallsBackToTheTranscript() {
        AnswerRequest request = assembler.assemble(TURNS, "", Optional.empty(), "   ", List.of());

        assertThat(request.conversation()).contains("Answer the interviewer's most recent question.");
    }

    @Test
    void keepsATypedQuestionOutOfTheCachedPrefix() {
        AnswerRequest request =
                assembler.assemble(TURNS, "background", Optional.empty(), "what about sharding?", List.of());

        assertThat(request.cachedBlocks()).noneMatch(block -> block.contains("sharding"));
        assertThat(request.conversation()).contains("sharding");
    }

    // --- follow-up memory --------------------------------------------------

    @Test
    void passesPriorExchangesThroughForFollowUps() {
        var earlier = new AnswerRequest.Exchange("Tell me about a hard bug.", "At Acme I traced a deadlock...");

        AnswerRequest request =
                assembler.assemble(TURNS, "", Optional.empty(), "Say that more simply.", List.of(earlier));

        assertThat(request.priorExchanges()).containsExactly(earlier);
    }

    @Test
    void priorExchangesNeverLeakIntoTheCachedPrefix() {
        // They change on every answer. In the prefix they would invalidate the
        // cache each time, which is the failure this split exists to prevent.
        var earlier = new AnswerRequest.Exchange("Tell me about a hard bug.", "At Acme I traced a deadlock...");

        AnswerRequest request = assembler.assemble(TURNS, "background", Optional.empty(), null, List.of(earlier));

        assertThat(request.cachedBlocks()).noneMatch(block -> block.contains("deadlock"));
        assertThat(request.conversation()).doesNotContain("deadlock");
    }

    @Test
    void theCachedPrefixIsIdenticalWithAndWithoutMemory() {
        var earlier = new AnswerRequest.Exchange("q", "a");

        var without = assembler.assemble(TURNS, "background", Optional.empty(), null, List.of());
        var with = assembler.assemble(TURNS, "background", Optional.empty(), "follow up", List.of(earlier));

        assertThat(with.cachedBlocks()).isEqualTo(without.cachedBlocks());
    }
}
