package ai.vader.server.limit;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a user stands against their hourly cap.
 *
 * <p>Exists because Phase 12c introduced a limit whose first sign was being
 * refused by it. A cap the user cannot see coming is indistinguishable from the
 * app breaking.
 */
@RestController
@RequestMapping("/v1/usage")
class UsageController {

    private final ModelCallLimiter limits;

    UsageController(ModelCallLimiter limits) {
        this.limits = limits;
    }

    record Usage(int remaining, int limit) {}

    /** The user id comes from the verified token, never from the request. */
    @GetMapping
    Usage usage(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return new Usage(limits.remaining(userId, System.currentTimeMillis()), ModelCallLimiter.MAX_CALLS_PER_HOUR);
    }
}
