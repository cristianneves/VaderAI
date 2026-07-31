package ai.vader.server.preferences;

import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session preferences. Same rule as the rest of the API: the user id comes from
 * the verified JWT, never from the body.
 */
@RestController
@RequestMapping("/v1/preferences")
class PreferencesController {

    private final PreferencesService preferences;

    PreferencesController(PreferencesService preferences) {
        this.preferences = preferences;
    }

    record LanguageOption(String code, String label) {}

    /** The choices ship with the response so the client never hard-codes the list. */
    record PreferencesView(String language, List<LanguageOption> languages) {}

    record UpdateRequest(@NotNull String language) {}

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping
    PreferencesView get(@AuthenticationPrincipal Jwt jwt) {
        return new PreferencesView(
                preferences.language(userIdOf(jwt)).code(),
                Arrays.stream(Language.values())
                        .map(language -> new LanguageOption(language.code(), language.label()))
                        .toList());
    }

    @PutMapping
    ResponseEntity<Void> update(@AuthenticationPrincipal Jwt jwt, @RequestBody UpdateRequest request) {
        preferences.setLanguage(userIdOf(jwt), request.language());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(PreferencesService.UnknownLanguageException.class)
    ResponseEntity<String> onUnknownLanguage(PreferencesService.UnknownLanguageException failed) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(failed.getMessage());
    }
}
