package ai.vader.server.limit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Caps how many billable model calls one user can make in an hour.
 *
 * <p>Every path that reaches Claude goes through here: the live answer stream,
 * practice generation and grading, and the practice report. Without it a client
 * stuck in a reconnect loop, or an interviewer who never stops talking, bills
 * Opus calls until someone notices the invoice.
 *
 * <p>A fixed window in a map, deliberately. {@code fly.toml} runs one machine
 * with {@code auto_stop_machines = "off"}, so there is no second instance to
 * share state with, and a distributed limiter would be a dependency bought to
 * solve a problem this deployment does not have. State is lost on restart,
 * which resets everyone's window — acceptable for a spend guard, and the reason
 * this is not the place to enforce a paid quota if one is ever added.
 *
 * <p>Time is passed in rather than read, so the behaviour is testable without
 * sleeping — the same shape as {@code TurnDetector}.
 */
@Component
public class ModelCallLimiter {

    /**
     * A real interview with a talkative interviewer produces something like
     * 60-100 asks in an hour. The auto-ask debounce is 2 s, so a runaway trigger
     * loop would produce 1,800. 120 clears the first and caps the second.
     */
    public static final int MAX_CALLS_PER_HOUR = 120;

    static final long WINDOW_MS = 3_600_000;

    /**
     * Entries are only swept once the map has grown past this. A UUID and a
     * record is on the order of 80 bytes, so this is about not leaking for a
     * month of uptime rather than about memory pressure.
     */
    private static final int SWEEP_ABOVE = 10_000;

    private record Window(long startedAtMs, int calls) {}

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    /** False when this user has spent their hour. */
    public boolean tryAcquire(UUID userId, long nowMs) {
        if (windows.size() > SWEEP_ABOVE) {
            windows.values().removeIf(window -> nowMs - window.startedAtMs() >= WINDOW_MS);
        }
        var updated = windows.compute(userId, (id, current) -> current == null || nowMs - current.startedAtMs() >= WINDOW_MS
                ? new Window(nowMs, 1)
                : new Window(current.startedAtMs(), current.calls() + 1));
        return updated.calls() <= MAX_CALLS_PER_HOUR;
    }

    /**
     * The REST form. {@code ApiExceptionHandler} already renders a
     * {@link ResponseStatusException} as RFC 9457 problem+json and the desktop
     * app already surfaces its detail, so a 429 needs nothing else on either
     * side.
     */
    public void require(UUID userId, long nowMs) {
        if (!tryAcquire(userId, nowMs)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "You have reached the hourly limit for AI requests. Try again in a little while.");
        }
    }
}
