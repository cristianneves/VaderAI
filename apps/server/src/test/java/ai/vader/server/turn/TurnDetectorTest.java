package ai.vader.server.turn;

import static ai.vader.server.stt.TranscriptEvent.CHANNEL_INTERVIEWER;
import static ai.vader.server.stt.TranscriptEvent.CHANNEL_USER;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TurnDetectorTest {

    private final TurnDetector detector = new TurnDetector();

    @Test
    void firesOnceTheSilenceWindowHasPassed() {
        detector.onTranscript(CHANNEL_INTERVIEWER, true, 1_000);

        assertThat(detector.pollAutoAsk(1_000 + TurnDetector.SILENCE_MS)).isTrue();
    }

    @Test
    void doesNotFireBeforeTheSilenceWindow() {
        detector.onTranscript(CHANNEL_INTERVIEWER, true, 1_000);

        assertThat(detector.pollAutoAsk(1_500)).isFalse();
    }

    @Test
    void doesNotFireWithoutAFinalSegment() {
        detector.onTranscript(CHANNEL_INTERVIEWER, false, 1_000);

        assertThat(detector.pollAutoAsk(5_000)).isFalse();
    }

    @Test
    void doesNotFireTwiceForOneQuestion() {
        detector.onTranscript(CHANNEL_INTERVIEWER, true, 1_000);

        assertThat(detector.pollAutoAsk(2_000)).isTrue();
        assertThat(detector.pollAutoAsk(9_000)).isFalse();
    }

    @Test
    void theUserSpeakingDisarmsTheQuestion() {
        detector.onTranscript(CHANNEL_INTERVIEWER, true, 1_000);
        // They started answering on their own; they do not need an answer generated.
        detector.onTranscript(CHANNEL_USER, false, 1_200);

        assertThat(detector.pollAutoAsk(5_000)).isFalse();
    }

    @Test
    void debounceDropsASecondQuestionArrivingTooSoon() {
        detector.onTranscript(CHANNEL_INTERVIEWER, true, 1_000);
        assertThat(detector.pollAutoAsk(1_700)).isTrue();

        detector.onTranscript(CHANNEL_INTERVIEWER, true, 1_800);

        assertThat(detector.pollAutoAsk(2_500)).isFalse();
    }

    @Test
    void firesAgainOnceTheDebounceWindowHasPassed() {
        detector.onTranscript(CHANNEL_INTERVIEWER, true, 1_000);
        assertThat(detector.pollAutoAsk(1_700)).isTrue();

        detector.onTranscript(CHANNEL_INTERVIEWER, true, 4_000);

        assertThat(detector.pollAutoAsk(4_700)).isTrue();
    }

    @Test
    void aManualAskStartsTheDebounceWindow() {
        detector.recordManualAsk(1_000);
        detector.onTranscript(CHANNEL_INTERVIEWER, true, 1_100);

        assertThat(detector.pollAutoAsk(1_900)).isFalse();
    }

    @Test
    void aManualAskDisarmsAPendingQuestion() {
        detector.onTranscript(CHANNEL_INTERVIEWER, true, 1_000);
        detector.recordManualAsk(1_100);

        assertThat(detector.pollAutoAsk(5_000)).isFalse();
    }
}
