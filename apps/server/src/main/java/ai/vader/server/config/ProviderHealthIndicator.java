package ai.vader.server.config;

import ai.vader.server.llm.AnthropicProperties;
import ai.vader.server.stt.DeepgramProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reports whether the two upstream providers are configured at all.
 *
 * <p>Both API keys default to empty so tests and local development can run
 * without them, which means a deployment missing one starts perfectly happily
 * and only fails at the first ask — as a vendor 401, an hour later, in front of
 * a user. This turns that into a DOWN health check at boot, which is what the
 * platform's health probe reads.
 *
 * <p>The health body hides component names from anonymous callers
 * ({@code show-details: when-authorized}), so the startup warning below is what
 * actually names the missing key in the log.
 */
@Component
class ProviderHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthIndicator.class);

    private final AnthropicProperties anthropic;
    private final DeepgramProperties deepgram;

    ProviderHealthIndicator(AnthropicProperties anthropic, DeepgramProperties deepgram) {
        this.anthropic = anthropic;
        this.deepgram = deepgram;
    }

    @Override
    public Health health() {
        var health = missingKeys().isEmpty() ? Health.up() : Health.down();
        return health.withDetail("anthropic", describe(anthropic.isConfigured(), "ANTHROPIC_API_KEY"))
                .withDetail("deepgram", describe(deepgram.isConfigured(), "DEEPGRAM_API_KEY"))
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnAboutMissingKeys() {
        for (String key : missingKeys()) {
            log.warn("{} is not set - every feature that needs it will fail at first use", key);
        }
    }

    private List<String> missingKeys() {
        var missing = new ArrayList<String>(2);
        if (!anthropic.isConfigured()) missing.add("ANTHROPIC_API_KEY");
        if (!deepgram.isConfigured()) missing.add("DEEPGRAM_API_KEY");
        return missing;
    }

    private static String describe(boolean configured, String key) {
        return configured ? "configured" : "missing " + key;
    }
}
