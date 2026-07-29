package ai.vader.server.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the real SDK against a local endpoint speaking the Messages API. No API
 * key involved — what this covers is the request we build, which is where a
 * structured-output or caching mistake would otherwise go unnoticed until it
 * silently stopped working.
 */
class AnthropicJsonEngineTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of("verdict", Map.of("type", "string")),
            "required",
            List.of("verdict"),
            "additionalProperties",
            false);

    private static final List<String> BLOCKS = List.of("system prompt", "candidate background");
    private static final String PROMPT = "Grade this answer: it was fine I guess";

    /** A thinking block sits ahead of the text, as it does on this model by default. */
    private static final String RESPONSE =
            """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
             "content":[{"type":"thinking","thinking":"","signature":"sig"},
                        {"type":"text","text":"{\\"verdict\\":\\"vague\\"}"}],
             "stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":700,"output_tokens":30,"cache_read_input_tokens":640,"cache_creation_input_tokens":0}}
            """;

    private MockWebServer server;
    private AnthropicJsonEngine engine;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        engine = new AnthropicJsonEngine(
                new AnthropicProperties("test-key", "claude-opus-5", 1024, false, server.url("/").toString()));
    }

    @AfterEach
    void stop() {
        try {
            server.shutdown();
        } catch (Exception ignored) {
            // already stopped
        }
    }

    private void enqueueResponse() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(RESPONSE));
    }

    private com.fasterxml.jackson.databind.JsonNode sentBody() throws Exception {
        return new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
    }

    @Test
    void returnsOnlyTheTextBlocks() {
        enqueueResponse();

        String body = engine.complete(BLOCKS, PROMPT, SCHEMA);

        // The empty thinking block must not end up concatenated into the JSON.
        assertThat(body).isEqualTo("{\"verdict\":\"vague\"}");
    }

    @Test
    void constrainsTheResponseToTheGivenSchema() throws Exception {
        enqueueResponse();

        engine.complete(BLOCKS, PROMPT, SCHEMA);

        var format = sentBody().get("output_config").get("format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        var schema = format.get("schema");
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.get("required").get(0).asText()).isEqualTo("verdict");
        assertThat(schema.get("properties").get("verdict").get("type").asText())
                .isEqualTo("string");
    }

    @Test
    void marksTheLastCachedBlockAsTheCacheBreakpoint() throws Exception {
        enqueueResponse();

        engine.complete(BLOCKS, PROMPT, SCHEMA);

        var system = sentBody().get("system");
        assertThat(system).hasSize(2);
        assertThat(system.get(0).has("cache_control")).isFalse();
        assertThat(system.get(1).get("cache_control").get("ttl").asText()).isEqualTo("1h");
    }

    @Test
    void keepsThePromptOutOfTheCachedPrefix() throws Exception {
        enqueueResponse();

        engine.complete(BLOCKS, PROMPT, SCHEMA);

        var body = sentBody();
        // The whole point of the split: the prompt changes per call, the prefix
        // does not.
        assertThat(body.get("system").toString()).doesNotContain("it was fine I guess");
        assertThat(body.get("messages").get(0).get("content").toString()).contains("it was fine I guess");
    }

    @Test
    void sendsABudgetLargeEnoughForThinkingAndAQuestionSet() throws Exception {
        enqueueResponse();

        engine.complete(BLOCKS, PROMPT, SCHEMA);

        var body = sentBody();
        assertThat(body.get("model").asText()).isEqualTo("claude-opus-5");
        // Not the 1024 configured for a single streamed answer: this budget also
        // has to cover thinking.
        assertThat(body.get("max_tokens").asLong()).isEqualTo(4096);
    }

    @Test
    void doesNotLowerEffort() throws Exception {
        enqueueResponse();

        engine.complete(BLOCKS, PROMPT, SCHEMA);

        // The streaming engine sets effort=low for latency; grading has no
        // deadline and should get the model's default.
        assertThat(sentBody().get("output_config").has("effort")).isFalse();
    }

    @Test
    void reportsAnApiFailure() {
        // The SDK retries 5xx twice by default; without a response queued for
        // each attempt MockWebServer would hold the connection open.
        for (int attempt = 0; attempt < 3; attempt++) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        }

        assertThatThrownBy(() -> engine.complete(BLOCKS, PROMPT, SCHEMA)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void omitsTheSecondBlockWhenThereIsOnlyOne() throws Exception {
        enqueueResponse();

        engine.complete(List.of("system prompt"), PROMPT, SCHEMA);

        var system = sentBody().get("system");
        assertThat(system).hasSize(1);
        assertThat(system.get(0).get("cache_control").get("ttl").asText()).isEqualTo("1h");
    }
}
