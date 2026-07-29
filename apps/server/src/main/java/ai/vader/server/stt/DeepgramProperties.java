package ai.vader.server.stt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param url overridable so tests can point the provider at a local fake
 * @param reconnectAttempts how many times a dropped Deepgram socket is retried
 *     before the session is told transcription failed
 */
@ConfigurationProperties("vaderai.deepgram")
public record DeepgramProperties(String apiKey, String url, int reconnectAttempts) {

    public DeepgramProperties {
        if (url == null || url.isBlank()) url = "wss://api.deepgram.com/v1/listen";
        if (reconnectAttempts <= 0) reconnectAttempts = 3;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
