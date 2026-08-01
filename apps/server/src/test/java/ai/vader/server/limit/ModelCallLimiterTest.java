package ai.vader.server.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ModelCallLimiterTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final long NOW = 1_700_000_000_000L;

    private final ModelCallLimiter limiter = new ModelCallLimiter();

    @Test
    void allowsEveryCallUpToTheCap() {
        for (int call = 1; call <= ModelCallLimiter.MAX_CALLS_PER_HOUR; call++) {
            assertThat(limiter.tryAcquire(USER, NOW)).as("call %d", call).isTrue();
        }
    }

    @Test
    void refusesTheCallAfterTheCap() {
        for (int call = 0; call < ModelCallLimiter.MAX_CALLS_PER_HOUR; call++) {
            limiter.tryAcquire(USER, NOW);
        }

        assertThat(limiter.tryAcquire(USER, NOW)).isFalse();
    }

    @Test
    void staysRefusedForTheRestOfTheWindow() {
        for (int call = 0; call <= ModelCallLimiter.MAX_CALLS_PER_HOUR; call++) {
            limiter.tryAcquire(USER, NOW);
        }

        assertThat(limiter.tryAcquire(USER, NOW + ModelCallLimiter.WINDOW_MS - 1)).isFalse();
    }

    @Test
    void startsAFreshWindowOnceTheHourHasPassed() {
        for (int call = 0; call <= ModelCallLimiter.MAX_CALLS_PER_HOUR; call++) {
            limiter.tryAcquire(USER, NOW);
        }

        assertThat(limiter.tryAcquire(USER, NOW + ModelCallLimiter.WINDOW_MS)).isTrue();
    }

    @Test
    void oneUserSpendingTheirHourDoesNotSpendAnother() {
        for (int call = 0; call <= ModelCallLimiter.MAX_CALLS_PER_HOUR; call++) {
            limiter.tryAcquire(USER, NOW);
        }

        assertThat(limiter.tryAcquire(OTHER, NOW)).isTrue();
    }

    @Test
    void reportsTheWholeAllowanceBeforeAnyCall() {
        assertThat(limiter.remaining(USER, NOW)).isEqualTo(ModelCallLimiter.MAX_CALLS_PER_HOUR);
    }

    @Test
    void countsDownAsCallsAreMade() {
        limiter.tryAcquire(USER, NOW);
        limiter.tryAcquire(USER, NOW);

        assertThat(limiter.remaining(USER, NOW)).isEqualTo(ModelCallLimiter.MAX_CALLS_PER_HOUR - 2);
    }

    @Test
    void readingWhatIsLeftDoesNotSpendAnything() {
        limiter.remaining(USER, NOW);
        limiter.remaining(USER, NOW);

        assertThat(limiter.remaining(USER, NOW)).isEqualTo(ModelCallLimiter.MAX_CALLS_PER_HOUR);
    }

    @Test
    void neverReportsFewerThanNoneLeft() {
        for (int call = 0; call < ModelCallLimiter.MAX_CALLS_PER_HOUR + 10; call++) {
            limiter.tryAcquire(USER, NOW);
        }

        assertThat(limiter.remaining(USER, NOW)).isZero();
    }

    @Test
    void theAllowanceIsWholeAgainInTheNextWindow() {
        for (int call = 0; call <= ModelCallLimiter.MAX_CALLS_PER_HOUR; call++) {
            limiter.tryAcquire(USER, NOW);
        }

        assertThat(limiter.remaining(USER, NOW + ModelCallLimiter.WINDOW_MS))
                .isEqualTo(ModelCallLimiter.MAX_CALLS_PER_HOUR);
    }

    @Test
    void requirePassesQuietlyUnderTheCap() {
        limiter.require(USER, NOW);

        assertThat(limiter.tryAcquire(USER, NOW)).isTrue();
    }

    @Test
    void requireThrowsA429OverTheCap() {
        for (int call = 0; call <= ModelCallLimiter.MAX_CALLS_PER_HOUR; call++) {
            limiter.tryAcquire(USER, NOW);
        }

        assertThatThrownBy(() -> limiter.require(USER, NOW))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(thrown -> ((ResponseStatusException) thrown).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
