package ai.vader.server.turn;

import ai.vader.server.stt.TranscriptEvent;

/**
 * Decides when the interviewer has finished asking something.
 *
 * <p>A question is armed when channel 0 produces a final segment and fires once
 * {@link #SILENCE_MS} of quiet has followed. Anything on the mic channel
 * disarms it: the user answering for themselves is the clearest possible signal
 * that they do not need an answer generated.
 *
 * <p>Time is passed in rather than read, so the behaviour is testable without
 * sleeping.
 */
public final class TurnDetector {

    /** Deepgram's endpointing already waits this long; the gate is belt and braces. */
    public static final long SILENCE_MS = 700;

    public static final long DEBOUNCE_MS = 2_000;

    private long armedAt = -1;
    private long lastAskAt;
    private boolean asked;

    public synchronized void onTranscript(int channel, boolean isFinal, long nowMs) {
        if (channel == TranscriptEvent.CHANNEL_USER) {
            armedAt = -1;
            return;
        }
        if (isFinal) armedAt = nowMs;
    }

    /**
     * Fires at most once per armed question. A question arriving inside the
     * debounce window is dropped rather than queued — answering it late, over
     * the top of the previous answer, is worse than not answering it.
     */
    public synchronized boolean pollAutoAsk(long nowMs) {
        if (armedAt < 0 || nowMs - armedAt < SILENCE_MS) return false;
        armedAt = -1;
        if (asked && nowMs - lastAskAt < DEBOUNCE_MS) return false;
        lastAskAt = nowMs;
        asked = true;
        return true;
    }

    /** A manual trigger always fires — the user pressed the key — but resets the window. */
    public synchronized void recordManualAsk(long nowMs) {
        armedAt = -1;
        lastAskAt = nowMs;
        asked = true;
    }
}
