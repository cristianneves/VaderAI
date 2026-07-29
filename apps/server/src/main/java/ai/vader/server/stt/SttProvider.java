package ai.vader.server.stt;

import java.nio.ByteBuffer;

/**
 * A streaming speech-to-text session. One instance per client session.
 *
 * <p>{@code SessionWebSocketHandler} depends on this interface and never on a
 * concrete provider — swapping Deepgram for another vendor, or for a local
 * model, must not reach into the session layer.
 */
public interface SttProvider extends AutoCloseable {

    void start(Listener listener);

    /** Feeds one PCM16 frame. Implementations must not block the caller. */
    void write(ByteBuffer audio);

    @Override
    void close();

    interface Listener {
        void onTranscript(TranscriptEvent event);

        void onError(Throwable cause);
    }
}
