package ai.vader.server.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param model model id — {@code claude-opus-5} unless overridden
 * @param maxTokens output ceiling for one answer
 * @param fastMode research preview, Claude API only, premium pricing. Off by
 *     default; it uses the beta endpoint and a different request path entirely.
 * @param baseUrl overridable so tests can point the SDK at a local fake
 */
@ConfigurationProperties("vaderai.anthropic")
public record AnthropicProperties(String apiKey, String model, long maxTokens, boolean fastMode, String baseUrl) {

    public AnthropicProperties {
        if (model == null || model.isBlank()) model = "claude-opus-5";
        if (maxTokens <= 0) maxTokens = 1024;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
