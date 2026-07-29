package ai.vader.server.stt;

/**
 * One transcript update.
 *
 * @param channel 0 = interviewer (system audio), 1 = user (mic). This comes
 *     straight from the audio channel the words arrived on, which is why
 *     attribution needs no diarization model.
 * @param isFinal interim results are relayed to the client but never persisted
 */
public record TranscriptEvent(int channel, String text, boolean isFinal) {

    public static final int CHANNEL_INTERVIEWER = 0;
    public static final int CHANNEL_USER = 1;
}
