package ai.vader.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The last stop for anything a controller did not handle itself.
 *
 * <p>Bodies are RFC 9457 {@code application/problem+json}. Extending
 * {@link ResponseEntityExceptionHandler} is most of the work: it already maps
 * Spring's own exceptions — malformed bodies, failed {@code @Valid}, wrong
 * method, an upload past the multipart limit — which until now fell through to
 * the default error page, a 500 with no explanation. This class only adds the
 * one case it does not cover.
 *
 * <p>The catch-all deliberately does not echo the exception message. Whatever
 * broke is logged here with its stack trace; the client gets one sentence,
 * because the alternative is the overlay showing a SQL error to someone in the
 * middle of an interview.
 *
 * <p>Controller-local {@code @ExceptionHandler} methods still win over this
 * one, and Spring Security's rejections never arrive: they are raised in the
 * filter chain, outside any dispatch.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * A controller that threw this already chose the status and the sentence.
     * Without this the catch-all below would match it too — it is an ordinary
     * RuntimeException — and turn every deliberate 404 into a 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail onResponseStatus(ResponseStatusException failed) {
        return failed.getBody();
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception failed) {
        log.error("unhandled exception", failed);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on the server.");
    }
}
