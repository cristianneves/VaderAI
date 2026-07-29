package ai.vader.server.session;

import ai.vader.server.stt.TranscriptEvent;
import java.util.ArrayDeque;
import java.util.List;

/**
 * The last N finalized turns, which is the conversation context the answer
 * engine gets in Phase 4. Bounded on purpose: an interview runs for an hour and
 * the prompt only ever wants the recent exchange.
 */
public final class TranscriptRingBuffer {

    private final ArrayDeque<TranscriptEvent> turns = new ArrayDeque<>();
    private final int capacity;

    public TranscriptRingBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized void add(TranscriptEvent event) {
        if (!event.isFinal()) throw new IllegalArgumentException("only final turns belong in the buffer");
        if (turns.size() == capacity) turns.removeFirst();
        turns.addLast(event);
    }

    public synchronized List<TranscriptEvent> snapshot() {
        return List.copyOf(turns);
    }

    public synchronized int size() {
        return turns.size();
    }
}
