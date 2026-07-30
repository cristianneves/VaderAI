package ai.vader.server.preferences;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PreferencesService {

    private final ProfileRepository profiles;

    PreferencesService(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    /**
     * Falls back to the default rather than failing. A session that cannot read
     * a preference should still run — in English, which is what it did before
     * the column existed.
     */
    public Language language(UUID userId) {
        return profiles.findLanguage(userId).flatMap(Language::ofCode).orElse(Language.DEFAULT);
    }

    /** @throws UnknownLanguageException if the code is not one we accept */
    public void setLanguage(UUID userId, String code) {
        Language language = Language.ofCode(code).orElseThrow(() -> new UnknownLanguageException(code));
        profiles.updateLanguage(userId, language.code());
    }

    public static class UnknownLanguageException extends RuntimeException {
        UnknownLanguageException(String code) {
            super("unsupported language: " + code);
        }
    }
}
