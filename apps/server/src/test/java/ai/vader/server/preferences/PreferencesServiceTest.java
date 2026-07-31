package ai.vader.server.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.vader.server.config.JdbcConversionsConfig;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs against the local Supabase Postgres, because the column being read and
 * written lives on a row that Supabase's own {@code handle_new_user} trigger
 * creates — there is no Java code that inserts a profile, so an in-memory
 * database would be testing a table we do not own.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PreferencesService.class, JdbcConversionsConfig.class})
@TestPropertySource(properties = "spring.datasource.hikari.connection-timeout=2000")
class PreferencesServiceTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PreferencesService preferences;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID alice;
    private UUID bob;

    @BeforeEach
    void seedUsers() {
        assumeTrue(databaseReachable(), "local Supabase Postgres is not running");
        alice = insertAuthUser("alice");
        bob = insertAuthUser("bob");
    }

    private boolean databaseReachable() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception unreachable) {
            return false;
        }
    }

    private UUID insertAuthUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into auth.users (id, instance_id, aud, role, email, encrypted_password,
                                        email_confirmed_at, created_at, updated_at)
                values (?, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
                        ?, '', now(), now(), now())
                """,
                id,
                id + name + "@example.com");
        return id;
    }

    @Test
    void aNewUserStartsInEnglish() {
        // The column default, which is what keeps every account that existed
        // before this migration behaving exactly as it did.
        assertThat(preferences.language(alice)).isEqualTo(Language.ENGLISH);
    }

    @Test
    void writesAndReadsBackALanguage() {
        preferences.setLanguage(alice, "pt-BR");

        assertThat(preferences.language(alice)).isEqualTo(Language.PORTUGUESE);
    }

    @Test
    void storesTheCodeTheSpeechModelExpects() {
        preferences.setLanguage(alice, "pt-BR");

        assertThat(jdbc.queryForObject("select language from public.profiles where id = ?", String.class, alice))
                .isEqualTo("pt-BR");
    }

    @Test
    void changingItAgainReplacesRatherThanAccumulates() {
        preferences.setLanguage(alice, "ja");
        preferences.setLanguage(alice, "multi");

        assertThat(preferences.language(alice)).isEqualTo(Language.MULTI);
        assertThat(jdbc.queryForObject("select count(*) from public.profiles where id = ?", Long.class, alice))
                .isEqualTo(1);
    }

    @Test
    void oneUsersLanguageDoesNotTouchAnothers() {
        preferences.setLanguage(alice, "ja");

        assertThat(preferences.language(bob)).isEqualTo(Language.ENGLISH);
    }

    @Test
    void rejectsAnUnknownCodeInsteadOfStoringIt() {
        assertThatThrownBy(() -> preferences.setLanguage(alice, "klingon"))
                .isInstanceOf(PreferencesService.UnknownLanguageException.class);

        assertThat(preferences.language(alice)).isEqualTo(Language.ENGLISH);
    }

    @Test
    void refusesAValueThatWouldTamperWithTheDeepgramQueryString() {
        // This is the reason the allow-list exists at all: the stored value is
        // interpolated into a URL.
        assertThatThrownBy(() -> preferences.setLanguage(alice, "en&punctuate=false"))
                .isInstanceOf(PreferencesService.UnknownLanguageException.class);
    }

    @Test
    void fallsBackToEnglishForAUserWithNoProfileRatherThanFailing() {
        // A session that cannot read a preference should still run.
        assertThat(preferences.language(UUID.randomUUID())).isEqualTo(Language.ENGLISH);
    }

    @Test
    void fallsBackToEnglishIfTheColumnHoldsSomethingWeNoLongerSupport() {
        // A language dropped from the enum must not take the session down with it.
        jdbc.update("update public.profiles set language = 'xx' where id = ?", alice);

        assertThat(preferences.language(alice)).isEqualTo(Language.ENGLISH);
    }
}
