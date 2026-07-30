package ai.vader.server.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param model model id — {@code claude-opus-5} unless overridden
 * @param maxTokens output ceiling for a spoken answer. Deliberately tight: this
 *     is read aloud by someone in a conversation.
 * @param codingMaxTokens ceiling for a technical answer. Higher because the same
 *     budget that comfortably holds three sentences truncates a function
 *     halfway through, and a half-written solution is worse than none.
 * @param fastMode research preview, Claude API only, premium pricing. Off by
 *     default; it uses the beta endpoint and a different request path entirely.
 * @param baseUrl overridable so tests can point the SDK at a local fake
 */
@ConfigurationProperties("vaderai.anthropic")
public record AnthropicProperties(
        String apiKey, String model, long maxTokens, long codingMaxTokens, boolean fastMode, String baseUrl) {

    public AnthropicProperties {
        if (model == null || model.isBlank()) model = "claude-opus-5";
        if (maxTokens <= 0) maxTokens = 1024;
        if (codingMaxTokens <= 0) codingMaxTokens = 2048;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** The ceiling for one request, which depends on what kind of answer it is. */
    public long maxTokensFor(AnswerMode mode) {
        return mode == AnswerMode.CODING ? codingMaxTokens : maxTokens;
    }
}
