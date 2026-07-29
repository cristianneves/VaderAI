package ai.vader.server.stt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DeepgramMessagesTest {

    private final ObjectMapper json = new ObjectMapper();

    private static String results(int channel, String transcript, boolean isFinal) {
        return """
               {"type":"Results","channel_index":[%d,2],"is_final":%s,
                "channel":{"alternatives":[{"transcript":"%s","confidence":0.98}]}}
               """
                .formatted(channel, isFinal, transcript);
    }

    @Test
    void mapsChannelZeroToTheInterviewer() throws Exception {
        var event = DeepgramMessages.parse(json.readTree(results(0, "Tell me about yourself", true)));

        assertThat(event).hasValueSatisfying(e -> {
            assertThat(e.channel()).isEqualTo(TranscriptEvent.CHANNEL_INTERVIEWER);
            assertThat(e.text()).isEqualTo("Tell me about yourself");
            assertThat(e.isFinal()).isTrue();
        });
    }

    @Test
    void mapsChannelOneToTheUser() throws Exception {
        var event = DeepgramMessages.parse(json.readTree(results(1, "I led the migration", false)));

        assertThat(event).hasValueSatisfying(e -> {
            assertThat(e.channel()).isEqualTo(TranscriptEvent.CHANNEL_USER);
            assertThat(e.isFinal()).isFalse();
        });
    }

    @Test
    void ignoresEmptyTranscripts() throws Exception {
        // Deepgram emits these constantly during silence.
        assertThat(DeepgramMessages.parse(json.readTree(results(0, "", true)))).isEmpty();
    }

    @Test
    void ignoresNonResultFrames() throws Exception {
        assertThat(DeepgramMessages.parse(json.readTree("{\"type\":\"Metadata\",\"duration\":1.0}"))).isEmpty();
        assertThat(DeepgramMessages.parse(json.readTree("{\"type\":\"SpeechStarted\"}"))).isEmpty();
        assertThat(DeepgramMessages.parse(json.readTree("{\"type\":\"UtteranceEnd\"}"))).isEmpty();
    }

    @Test
    void survivesAResultWithNoChannelIndex() throws Exception {
        var event = DeepgramMessages.parse(json.readTree(
                "{\"type\":\"Results\",\"is_final\":true,\"channel\":{\"alternatives\":[{\"transcript\":\"hi\"}]}}"));

        assertThat(event).hasValueSatisfying(e -> assertThat(e.channel()).isZero());
    }

    @Test
    void survivesAResultWithNoAlternatives() throws Exception {
        assertThat(DeepgramMessages.parse(json.readTree("{\"type\":\"Results\",\"channel\":{}}"))).isEmpty();
    }
}
