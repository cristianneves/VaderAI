package ai.vader.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.vader.server.llm.AnthropicProperties;
import ai.vader.server.stt.DeepgramProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * The point of this indicator is that a deployment missing an API key says so
 * at boot instead of at the first ask, so the negative cases are the ones that
 * matter.
 */
class ProviderHealthIndicatorTest {

    private static Health healthWith(String anthropicKey, String deepgramKey) {
        return new ProviderHealthIndicator(
                        new AnthropicProperties(anthropicKey, "claude-opus-5", 1024, 2048, false, ""),
                        new DeepgramProperties(deepgramKey, "wss://example.invalid", 3))
                .health();
    }

    @Test
    void reportsUpWhenBothKeysArePresent() {
        Health health = healthWith("sk-ant-xxx", "dg-xxx");

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("anthropic", "configured").containsEntry("deepgram", "configured");
    }

    @Test
    void reportsDownAndNamesTheVariableWhenTheModelKeyIsMissing() {
        Health health = healthWith("", "dg-xxx");

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("anthropic", "missing ANTHROPIC_API_KEY");
        assertThat(health.getDetails()).containsEntry("deepgram", "configured");
    }

    @Test
    void reportsDownWhenTheTranscriptionKeyIsMissing() {
        Health health = healthWith("sk-ant-xxx", "");

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("deepgram", "missing DEEPGRAM_API_KEY");
    }

    @Test
    void namesBothWhenNeitherIsSet() {
        Health health = healthWith(null, null);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("anthropic", "missing ANTHROPIC_API_KEY")
                .containsEntry("deepgram", "missing DEEPGRAM_API_KEY");
    }

    /** Whitespace is what a half-filled .env produces, and it is not a key. */
    @Test
    void treatsABlankKeyAsMissing() {
        assertThat(healthWith("   ", "dg-xxx").getStatus()).isEqualTo(Status.DOWN);
    }
}
