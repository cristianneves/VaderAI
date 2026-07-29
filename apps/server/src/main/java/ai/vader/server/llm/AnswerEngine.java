package ai.vader.server.llm;

/**
 * Streams an answer for one trigger. The session layer depends on this and never
 * on a model vendor's types.
 */
public interface AnswerEngine {

    /**
     * Starts streaming. Returns immediately; the listener is called from another
     * thread. Closing the returned handle cancels an answer still in flight —
     * which happens every time a new question arrives before the last one
     * finished.
     */
    AnswerStream stream(AnswerRequest request, Listener listener);

    interface Listener {
        void onDelta(String text);

        void onComplete(AnswerUsage usage);

        void onError(Throwable cause);
    }

    interface AnswerStream extends AutoCloseable {
        @Override
        void close();
    }

    /** Cache reads are the number worth watching — they should be non-zero after the first answer. */
    record AnswerUsage(long inputTokens, long outputTokens, long cacheReadInputTokens, long cacheCreationInputTokens) {}
}
