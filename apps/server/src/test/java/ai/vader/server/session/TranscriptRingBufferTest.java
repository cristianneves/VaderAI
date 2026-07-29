package ai.vader.server.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.vader.server.stt.TranscriptEvent;
import org.junit.jupiter.api.Test;

class TranscriptRingBufferTest {

    private static TranscriptEvent finalTurn(String text) {
        return new TranscriptEvent(TranscriptEvent.CHANNEL_INTERVIEWER, text, true);
    }

    @Test
    void keepsTurnsInOrder() {
        var buffer = new TranscriptRingBuffer(3);
        buffer.add(finalTurn("one"));
        buffer.add(finalTurn("two"));

        assertThat(buffer.snapshot()).extracting(TranscriptEvent::text).containsExactly("one", "two");
    }

    @Test
    void dropsTheOldestOncePastCapacity() {
        var buffer = new TranscriptRingBuffer(2);
        buffer.add(finalTurn("one"));
        buffer.add(finalTurn("two"));
        buffer.add(finalTurn("three"));

        assertThat(buffer.size()).isEqualTo(2);
        assertThat(buffer.snapshot()).extracting(TranscriptEvent::text).containsExactly("two", "three");
    }

    @Test
    void rejectsInterimTurns() {
        var buffer = new TranscriptRingBuffer(2);

        assertThatThrownBy(() -> buffer.add(new TranscriptEvent(0, "half a sen", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("final");
    }

    @Test
    void rejectsANonPositiveCapacity() {
        assertThatThrownBy(() -> new TranscriptRingBuffer(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotIsUnaffectedByLaterTurns() {
        var buffer = new TranscriptRingBuffer(3);
        buffer.add(finalTurn("one"));
        var taken = buffer.snapshot();
        buffer.add(finalTurn("two"));

        assertThat(taken).hasSize(1);
    }
}
