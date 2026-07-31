package ai.vader.server.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaBase64ImageSource;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaImageBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Streams answers from Claude.
 *
 * <p>The knowledge base sits in a cached prefix with a one-hour TTL: an
 * interview is a long series of requests against an identical prefix, which is
 * exactly the shape prompt caching pays off on. Effort is the latency lever —
 * {@code LOW} buys first tokens sooner, and thinking stays on because it is the
 * default on this model and turning it off costs answer quality.
 */
@Component
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicAnswerEngine implements AnswerEngine {

    private final AnthropicClient client;
    private final AnthropicProperties properties;

    AnthropicAnswerEngine(AnthropicProperties properties) {
        this.properties = properties;
        var builder = AnthropicOkHttpClient.builder()
                .apiKey(properties.isConfigured() ? properties.apiKey() : "unset")
                // The SDK's default timeout for a streaming request is at least
                // ten minutes. An answer that has not arrived within one minute
                // is useless to someone mid-interview — fail and let them retry.
                .timeout(Duration.ofSeconds(60));
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) builder.baseUrl(properties.baseUrl());
        this.client = builder.build();
    }

    @Override
    public AnswerStream stream(AnswerRequest request, Listener listener) {
        return properties.fastMode() ? streamFast(request, listener) : streamStandard(request, listener);
    }

    private AnswerStream streamStandard(AnswerRequest request, Listener listener) {
        var cancelled = new AtomicBoolean();
        var accumulator = MessageAccumulator.create();
        StreamResponse<RawMessageStreamEvent> response;
        try {
            // The SDK reports HTTP failures here, synchronously, before any
            // streaming begins. Letting this escape would leave the client
            // waiting on an answer that never starts.
            response = client.messages().createStreaming(params(request));
        } catch (Exception failed) {
            listener.onError(failed);
            return () -> {};
        }

        Thread.ofVirtual().name("answer-stream").start(() -> {
            try (response) {
                response.stream()
                        .peek(accumulator::accumulate)
                        .flatMap(event -> event.contentBlockDelta().stream())
                        .flatMap(delta -> delta.delta().text().stream())
                        .forEach(text -> listener.onDelta(text.text()));
                listener.onComplete(usageOf(accumulator.message()));
            } catch (Exception failed) {
                // A cancelled stream throws on the next read; that is expected,
                // not an error worth showing anyone.
                if (!cancelled.get()) listener.onError(failed);
            }
        });

        return () -> {
            cancelled.set(true);
            response.close();
        };
    }

    /**
     * Fast mode runs the same model faster at premium pricing. It exists only on
     * the beta endpoint, which has a parallel type hierarchy — hence the near
     * duplicate below rather than a shared builder.
     */
    private AnswerStream streamFast(AnswerRequest request, Listener listener) {
        var cancelled = new AtomicBoolean();
        var accumulator = BetaMessageAccumulator.create();
        StreamResponse<BetaRawMessageStreamEvent> response;
        try {
            response = client.beta().messages().createStreaming(betaParams(request));
        } catch (Exception failed) {
            listener.onError(failed);
            return () -> {};
        }

        Thread.ofVirtual().name("answer-stream-fast").start(() -> {
            try (response) {
                response.stream()
                        .peek(accumulator::accumulate)
                        .flatMap(event -> event.contentBlockDelta().stream())
                        .flatMap(delta -> delta.delta().text().stream())
                        .forEach(text -> listener.onDelta(text.text()));
                listener.onComplete(usageOf(accumulator.message()));
            } catch (Exception failed) {
                if (!cancelled.get()) listener.onError(failed);
            }
        });

        return () -> {
            cancelled.set(true);
            response.close();
        };
    }

    private MessageCreateParams params(AnswerRequest request) {
        List<TextBlockParam> system = new ArrayList<>();
        for (int i = 0; i < request.cachedBlocks().size(); i++) {
            var block = TextBlockParam.builder().text(request.cachedBlocks().get(i));
            // One breakpoint, on the last stable block: it caches everything
            // before it, and the volatile turn context lives in the user message
            // after it.
            if (i == request.cachedBlocks().size() - 1) {
                block.cacheControl(CacheControlEphemeral.builder()
                        .ttl(CacheControlEphemeral.Ttl.TTL_1H)
                        .build());
            }
            system.add(block.build());
        }

        List<ContentBlockParam> content = new ArrayList<>();
        // Claude reads images best when they come before the text that asks
        // about them.
        request.image().ifPresent(image -> content.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.of(image.mediaType()))
                        .data(image.base64Data())
                        .build())
                .build())));
        content.add(ContentBlockParam.ofText(
                TextBlockParam.builder().text(request.conversation()).build()));

        var params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokensFor(request.mode()))
                .systemOfTextBlockParams(system)
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .build());
        // Replayed as real turns rather than narrated inside the user message:
        // "explain that more simply" needs the previous answer to be something
        // the model said, not something it is being told it said.
        for (AnswerRequest.Exchange exchange : request.priorExchanges()) {
            params.addUserMessage(exchange.asked());
            params.addAssistantMessage(exchange.answer());
        }
        return params.addUserMessageOfBlockParams(content).build();
    }

    private com.anthropic.models.beta.messages.MessageCreateParams betaParams(AnswerRequest request) {
        List<BetaTextBlockParam> system = new ArrayList<>();
        for (int i = 0; i < request.cachedBlocks().size(); i++) {
            var block = BetaTextBlockParam.builder().text(request.cachedBlocks().get(i));
            if (i == request.cachedBlocks().size() - 1) {
                block.cacheControl(BetaCacheControlEphemeral.builder()
                        .ttl(BetaCacheControlEphemeral.Ttl.TTL_1H)
                        .build());
            }
            system.add(block.build());
        }

        List<BetaContentBlockParam> content = new ArrayList<>();
        request.image().ifPresent(image -> content.add(BetaContentBlockParam.ofImage(BetaImageBlockParam.builder()
                .source(BetaBase64ImageSource.builder()
                        .mediaType(BetaBase64ImageSource.MediaType.of(image.mediaType()))
                        .data(image.base64Data())
                        .build())
                .build())));
        content.add(BetaContentBlockParam.ofText(
                BetaTextBlockParam.builder().text(request.conversation()).build()));

        var params = com.anthropic.models.beta.messages.MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokensFor(request.mode()))
                .addBeta(AnthropicBeta.FAST_MODE_2026_02_01)
                .speed(com.anthropic.models.beta.messages.MessageCreateParams.Speed.FAST)
                .systemOfBetaTextBlockParams(system)
                .outputConfig(BetaOutputConfig.builder()
                        .effort(BetaOutputConfig.Effort.LOW)
                        .build());
        for (AnswerRequest.Exchange exchange : request.priorExchanges()) {
            params.addUserMessage(exchange.asked());
            params.addAssistantMessage(exchange.answer());
        }
        return params.addUserMessageOfBetaContentBlockParams(content).build();
    }

    private static AnswerUsage usageOf(Message message) {
        Usage usage = message.usage();
        return new AnswerUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0L),
                usage.cacheCreationInputTokens().orElse(0L));
    }

    private static AnswerUsage usageOf(BetaMessage message) {
        BetaUsage usage = message.usage();
        return new AnswerUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0L),
                usage.cacheCreationInputTokens().orElse(0L));
    }
}
