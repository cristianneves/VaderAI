package ai.vader.server.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the real SDK against a local endpoint that speaks the Messages API's
 * SSE format. No API key involved — this covers the request we build (cache
 * breakpoint, effort, image placement) and the stream we consume.
 */
class AnthropicAnswerEngineTest {

    private MockWebServer server;
    private AnthropicAnswerEngine engine;

    private final List<String> deltas = new CopyOnWriteArrayList<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final AtomicReference<AnswerEngine.AnswerUsage> usage = new AtomicReference<>();

    private final AnswerEngine.Listener listener = new AnswerEngine.Listener() {
        @Override
        public void onDelta(String text) {
            deltas.add(text);
        }

        @Override
        public void onComplete(AnswerEngine.AnswerUsage completed) {
            usage.set(completed);
        }

        @Override
        public void onError(Throwable cause) {
            errors.add(cause);
        }
    };

    private static final String SSE =
            """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":120,"output_tokens":0,"cache_read_input_tokens":900,"cache_creation_input_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"At Acme I"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" cut deploy time."}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":42}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private static AnswerRequest request(Optional<AnswerRequest.ImageInput> image) {
        return new AnswerRequest(List.of("system prompt", "knowledge base"), "Interviewer: hello", image);
    }

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        engine = new AnthropicAnswerEngine(new AnthropicProperties(
                "test-key", "claude-opus-5", 1024, false, server.url("/").toString()));
    }

    @AfterEach
    void stop() {
        try {
            server.shutdown();
        } catch (Exception ignored) {
            // already stopped
        }
    }

    private void enqueueStream() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(SSE));
    }

    @Test
    void streamsTextDeltasInOrder() {
        enqueueStream();

        engine.stream(request(Optional.empty()), listener);

        await().atMost(Duration.ofSeconds(10)).until(() -> usage.get() != null);
        assertThat(deltas).containsExactly("At Acme I", " cut deploy time.");
        assertThat(errors).isEmpty();
    }

    @Test
    void reportsUsageIncludingCacheReads() {
        enqueueStream();

        engine.stream(request(Optional.empty()), listener);

        await().atMost(Duration.ofSeconds(10)).until(() -> usage.get() != null);
        assertThat(usage.get().cacheReadInputTokens()).isEqualTo(900);
        assertThat(usage.get().inputTokens()).isEqualTo(120);
        assertThat(usage.get().outputTokens()).isEqualTo(42);
    }

    @Test
    void marksTheLastCachedBlockAsTheCacheBreakpoint() throws Exception {
        enqueueStream();

        engine.stream(request(Optional.empty()), listener);
        await().atMost(Duration.ofSeconds(10)).until(() -> usage.get() != null);

        var body = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
        var system = body.get("system");
        assertThat(system).hasSize(2);
        // Only the last stable block carries the breakpoint; everything before it
        // is cached with it.
        assertThat(system.get(0).has("cache_control")).isFalse();
        assertThat(system.get(1).get("cache_control").get("ttl").asText()).isEqualTo("1h");
    }

    @Test
    void sendsEffortAndKeepsTheTranscriptOutOfTheCachedPrefix() throws Exception {
        enqueueStream();

        engine.stream(request(Optional.empty()), listener);
        await().atMost(Duration.ofSeconds(10)).until(() -> usage.get() != null);

        var body = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("low");
        assertThat(body.get("system").toString()).doesNotContain("Interviewer: hello");
        assertThat(body.get("messages").get(0).get("content").toString()).contains("Interviewer: hello");
    }

    @Test
    void placesTheImageBeforeTheTextBlock() throws Exception {
        enqueueStream();

        engine.stream(request(Optional.of(new AnswerRequest.ImageInput("image/png", "QUJD"))), listener);
        await().atMost(Duration.ofSeconds(10)).until(() -> usage.get() != null);

        var content = new ObjectMapper()
                .readTree(server.takeRequest().getBody().readUtf8())
                .get("messages")
                .get(0)
                .get("content");
        assertThat(content.get(0).get("type").asText()).isEqualTo("image");
        assertThat(content.get(0).get("source").get("media_type").asText()).isEqualTo("image/png");
        assertThat(content.get(1).get("type").asText()).isEqualTo("text");
    }

    @Test
    void cancellingAnAnswerReportsNoError() {
        enqueueStream();

        AnswerEngine.AnswerStream stream = engine.stream(request(Optional.empty()), listener);
        stream.close();

        // A cancelled stream is a normal event — the next question replaced this one.
        assertThat(errors).isEmpty();
    }

    @Test
    void reportsAnApiFailure() {
        // The SDK retries 5xx twice by default; without a queued response for
        // each attempt, MockWebServer would hold the connection open.
        for (int attempt = 0; attempt < 3; attempt++) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        }

        engine.stream(request(Optional.empty()), listener);

        await().atMost(Duration.ofSeconds(15)).until(() -> !errors.isEmpty());
        assertThat(usage.get()).isNull();
    }

    @Test
    void sendsTheConfiguredModelAndTokenCeiling() throws Exception {
        enqueueStream();

        engine.stream(request(Optional.empty()), listener);
        await().atMost(Duration.ofSeconds(10)).until(() -> usage.get() != null);

        RecordedRequest recorded = server.takeRequest();
        var body = new ObjectMapper().readTree(recorded.getBody().readUtf8());
        assertThat(body.get("model").asText()).isEqualTo("claude-opus-5");
        assertThat(body.get("max_tokens").asLong()).isEqualTo(1024);
    }
}
