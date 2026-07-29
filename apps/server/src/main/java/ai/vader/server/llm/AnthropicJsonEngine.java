package ai.vader.server.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Blocking, schema-constrained calls to Claude.
 *
 * <p>The response format is enforced by the API rather than requested in the
 * prompt, so callers never have to strip a markdown fence or handle a model that
 * decided to explain itself first.
 */
@Component
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicJsonEngine implements JsonEngine {

    private static final Logger log = LoggerFactory.getLogger(AnthropicJsonEngine.class);

    /**
     * Deliberately not {@code properties.maxTokens()} (1024, sized for one spoken
     * answer). This budget covers thinking as well as output on this model, and
     * 1024 truncates a five-question set part-way through the JSON.
     */
    private static final long MAX_TOKENS = 4096;

    private final AnthropicClient client;
    private final AnthropicProperties properties;

    AnthropicJsonEngine(AnthropicProperties properties) {
        this.properties = properties;
        var builder = AnthropicOkHttpClient.builder()
                .apiKey(properties.isConfigured() ? properties.apiKey() : "unset")
                // Longer than the streaming engine's 60s: nobody is waiting
                // mid-sentence for a grade, and thinking at default effort takes
                // its time.
                .timeout(Duration.ofSeconds(120));
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) builder.baseUrl(properties.baseUrl());
        this.client = builder.build();
    }

    @Override
    public String complete(List<String> cachedBlocks, String prompt, Map<String, Object> schema) {
        Message message = client.messages().create(params(cachedBlocks, prompt, schema));
        var usage = message.usage();
        log.info(
                "json completion tokens in={} out={} cacheRead={} cacheWrite={}",
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0L),
                usage.cacheCreationInputTokens().orElse(0L));
        return textOf(message);
    }

    private MessageCreateParams params(List<String> cachedBlocks, String prompt, Map<String, Object> schema) {
        List<TextBlockParam> system = new ArrayList<>();
        for (int i = 0; i < cachedBlocks.size(); i++) {
            var block = TextBlockParam.builder().text(cachedBlocks.get(i));
            // Same split as the streaming engine: one breakpoint on the last
            // stable block, everything volatile in the user turn after it. Every
            // call in a practice session shares this prefix.
            if (i == cachedBlocks.size() - 1) {
                block.cacheControl(CacheControlEphemeral.builder()
                        .ttl(CacheControlEphemeral.Ttl.TTL_1H)
                        .build());
            }
            system.add(block.build());
        }

        return MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(system)
                .addUserMessage(prompt)
                // No effort override. The streaming engine drops to LOW to get
                // first tokens out fast; grading has no such deadline and reads
                // better with the default.
                .outputConfig(OutputConfig.builder()
                        .format(JsonOutputFormat.builder()
                                .type(JsonValue.from("json_schema"))
                                .schema(schemaOf(schema))
                                .build())
                        .build())
                .build();
    }

    /** {@code Schema} is a free-form property bag, so the JSON Schema goes in wholesale. */
    private static JsonOutputFormat.Schema schemaOf(Map<String, Object> schema) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        schema.forEach((key, value) -> fields.put(key, JsonValue.from(value)));
        return JsonOutputFormat.Schema.builder()
                .putAllAdditionalProperties(fields)
                .build();
    }

    /** Thinking blocks share the content array; only the text blocks carry the JSON. */
    private static String textOf(Message message) {
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }
}
