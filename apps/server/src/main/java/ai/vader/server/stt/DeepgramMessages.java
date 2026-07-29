package ai.vader.server.stt;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * Parsing of Deepgram's streaming JSON, kept apart from the socket so it can be
 * tested directly. Deepgram also sends Metadata, SpeechStarted and UtteranceEnd
 * frames; everything that is not a transcript with text is ignored.
 */
final class DeepgramMessages {

    private DeepgramMessages() {}

    static Optional<TranscriptEvent> parse(JsonNode root) {
        if (!"Results".equals(root.path("type").asText())) return Optional.empty();

        String text = root.path("channel").path("alternatives").path(0).path("transcript").asText("");
        if (text.isBlank()) return Optional.empty();

        // channel_index is [channel, totalChannels] — the first element is the
        // audio channel the words came from, which is our speaker attribution.
        JsonNode index = root.path("channel_index");
        int channel = index.isArray() && !index.isEmpty() ? index.get(0).asInt() : 0;

        return Optional.of(new TranscriptEvent(channel, text, root.path("is_final").asBoolean(false)));
    }
}
