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

    @Test
    void putsTheSystemPromptInTheCachedPrefix() {
        AnswerRequest request = assembler.assemble(TURNS, "", Optional.empty());

        assertThat(request.cachedBlocks()).hasSize(1);
        assertThat(request.cachedBlocks().get(0)).isEqualTo(PromptAssembler.SYSTEM_PROMPT);
    }

    @Test
    void putsTheKnowledgeBaseInTheCachedPrefixToo() {
        AnswerRequest request = assembler.assemble(TURNS, "Cut deploy time from 40 to 6 minutes.", Optional.empty());

        assertThat(request.cachedBlocks()).hasSize(2);
        assertThat(request.cachedBlocks().get(1)).contains("Cut deploy time from 40 to 6 minutes.");
    }

    @Test
    void keepsTheTranscriptOutOfTheCachedPrefix() {
        // This is the whole point of the split: a turn leaking into the cached
        // blocks would invalidate the cache on every single question.
        AnswerRequest request = assembler.assemble(TURNS, "background", Optional.empty());

        assertThat(request.cachedBlocks()).noneMatch(block -> block.contains("hard bug"));
        assertThat(request.conversation()).contains("hard bug");
    }

    @Test
    void labelsEachSpeaker() {
        AnswerRequest request = assembler.assemble(TURNS, "", Optional.empty());

        assertThat(request.conversation())
                .contains("Interviewer: Tell me about a hard bug.")
                .contains("You: Sure, at Acme...");
    }

    @Test
    void isStableAcrossCallsWithTheSameInput() {
        var first = assembler.assemble(TURNS, "background", Optional.empty());
        var second = assembler.assemble(TURNS, "background", Optional.empty());

        assertThat(first.cachedBlocks()).isEqualTo(second.cachedBlocks());
    }

    @Test
    void handlesAnEmptyTranscript() {
        AnswerRequest request = assembler.assemble(List.of(), "", Optional.empty());

        assertThat(request.conversation()).contains("no transcript yet");
    }

    @Test
    void pointsTheQuestionAtTheScreenWhenAnImageIsAttached() {
        var image = new AnswerRequest.ImageInput("image/png", "AAAA");

        AnswerRequest request = assembler.assemble(TURNS, "", Optional.of(image));

        assertThat(request.image()).contains(image);
        assertThat(request.conversation()).contains("on the screen");
    }

    @Test
    void blankKnowledgeBaseAddsNoBlock() {
        assertThat(assembler.assemble(TURNS, "   ", Optional.empty()).cachedBlocks())
                .hasSize(1);
    }
}
