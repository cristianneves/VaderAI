package ai.vader.server.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(DeepgramProperties.class)
public class DeepgramSttProviderFactory implements SttProviderFactory {

    private final OkHttpClient http;
    private final ObjectMapper json;
    private final DeepgramProperties properties;

    public DeepgramSttProviderFactory(ObjectMapper json, DeepgramProperties properties) {
        this.json = json;
        this.properties = properties;
        this.http = new OkHttpClient.Builder()
                // Deepgram sends nothing while nobody speaks; without pings an
                // idle proxy will drop the socket mid-interview.
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public SttProvider create(String languageCode) {
        return new DeepgramSttProvider(http, json, properties, languageCode);
    }
}
