package ai.vader.server.preferences;

import java.util.Arrays;
import java.util.Optional;

/**
 * The languages a session can run in.
 *
 * <p>This is an allow-list, not a suggestion. The code is interpolated into the
 * Deepgram query string, so anything reaching {@code DeepgramSttProvider} has to
 * have been matched against this enum first — a free-text column would put a
 * user-supplied string into a URL.
 *
 * <p>{@code MULTI} is Deepgram's code-switching mode: one session, several
 * languages, which is the case a fixed code handles badly — an interview held in
 * Portuguese that keeps saying "deployment" and "pull request" in English.
 */
public enum Language {
    MULTI("multi", "Multilingual", "Multilingual"),
    ENGLISH("en", "English", "English"),
    SPANISH("es", "Español", "Spanish"),
    PORTUGUESE("pt-BR", "Português (Brasil)", "Brazilian Portuguese"),
    FRENCH("fr", "Français", "French"),
    GERMAN("de", "Deutsch", "German"),
    ITALIAN("it", "Italiano", "Italian"),
    DUTCH("nl", "Nederlands", "Dutch"),
    HINDI("hi", "हिन्दी", "Hindi"),
    RUSSIAN("ru", "Русский", "Russian"),
    JAPANESE("ja", "日本語", "Japanese"),
    KOREAN("ko", "한국어", "Korean"),
    CHINESE("zh", "中文", "Mandarin Chinese");

    public static final Language DEFAULT = ENGLISH;

    private final String code;
    private final String label;
    private final String englishName;

    Language(String code, String label, String englishName) {
        this.code = code;
        this.label = label;
        this.englishName = englishName;
    }

    /** The Deepgram {@code language} parameter. */
    public String code() {
        return code;
    }

    /** How the language names itself, for the settings dropdown. */
    public String label() {
        return label;
    }

    /**
     * What the answer prompt is told to write in. English rather than the
     * endonym because the instruction sits in an English system prompt, and
     * {@link #MULTI} has no single answer — it means "whatever they are
     * speaking".
     */
    public String englishName() {
        return englishName;
    }

    public static Optional<Language> ofCode(String code) {
        return Arrays.stream(values())
                .filter(language -> language.code.equals(code))
                .findFirst();
    }
}
