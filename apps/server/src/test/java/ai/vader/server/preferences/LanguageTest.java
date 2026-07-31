package ai.vader.server.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LanguageTest {

    @Test
    void resolvesAKnownCode() {
        assertThat(Language.ofCode("pt-BR")).contains(Language.PORTUGUESE);
        assertThat(Language.ofCode("multi")).contains(Language.MULTI);
    }

    @Test
    void rejectsAnUnknownCode() {
        assertThat(Language.ofCode("klingon")).isEmpty();
        assertThat(Language.ofCode("")).isEmpty();
    }

    @Test
    void rejectsAnythingThatWouldTamperWithTheDeepgramQueryString() {
        // The code is interpolated into a URL. This enum is the boundary that
        // stops a request body from adding parameters of its own.
        assertThat(Language.ofCode("en&punctuate=false")).isEmpty();
        assertThat(Language.ofCode("en#")).isEmpty();
        assertThat(Language.ofCode("../listen")).isEmpty();
    }

    @Test
    void isCaseSensitiveBecauseDeepgramIs() {
        assertThat(Language.ofCode("PT-BR")).isEmpty();
        assertThat(Language.ofCode("EN")).isEmpty();
    }

    @Test
    void everyCodeIsUrlSafeAndUnique() {
        var codes = Arrays.stream(Language.values()).map(Language::code).collect(Collectors.toSet());

        assertThat(codes).hasSize(Language.values().length);
        assertThat(codes).allMatch(code -> code.matches("[a-z]{2}(-[A-Za-z0-9]+)?|multi"));
    }

    @Test
    void everyLanguageHasSomethingToPutInThePromptAndTheDropdown() {
        for (Language language : Language.values()) {
            assertThat(language.label()).isNotBlank();
            assertThat(language.englishName()).isNotBlank();
        }
    }

    @Test
    void theDefaultIsEnglishSoBehaviourIsUnchangedForExistingUsers() {
        assertThat(Language.DEFAULT).isEqualTo(Language.ENGLISH);
        assertThat(Language.DEFAULT.code()).isEqualTo("en");
    }
}
