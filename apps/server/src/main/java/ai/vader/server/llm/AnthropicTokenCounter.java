package ai.vader.server.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCountTokensParams;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Counts tokens with the API's own tokenizer, which is the only way to get the
 * number right — third-party tokenizers are calibrated for other models and are
 * wrong for Claude, badly so on code.
 *
 * <p>When there is no API key, or the call fails, it falls back to a crude
 * character ratio and marks the result estimated so nothing downstream presents
 * a guess as a measurement.
 */
@Component
public class AnthropicTokenCounter implements TokenCounter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicTokenCounter.class);
    /** Roughly four characters per token for English prose. Only used as a fallback. */
    private static final int CHARS_PER_TOKEN = 4;

    private final AnthropicClient client;
    private final AnthropicProperties properties;

    AnthropicTokenCounter(AnthropicProperties properties) {
        this.properties = properties;
        var builder = AnthropicOkHttpClient.builder()
                .apiKey(properties.isConfigured() ? properties.apiKey() : "unset")
                .timeout(Duration.ofSeconds(10));
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) builder.baseUrl(properties.baseUrl());
        this.client = builder.build();
    }

    @Override
    public TokenCount count(String text) {
        if (text == null || text.isBlank()) return new TokenCount(0, false);
        if (!properties.isConfigured()) return estimate(text);

        try {
            long tokens = client.messages()
                    .countTokens(MessageCountTokensParams.builder()
                            .model(properties.model())
                            .addUserMessage(text)
                            .build())
                    .inputTokens();
            return new TokenCount(tokens, false);
        } catch (Exception failed) {
            log.debug("token counting failed, falling back to an estimate", failed);
            return estimate(text);
        }
    }

    private TokenCount estimate(String text) {
        return new TokenCount(Math.max(1, text.length() / CHARS_PER_TOKEN), true);
    }
}
