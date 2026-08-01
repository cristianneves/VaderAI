package ai.vader.server.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.vader.server.config.JdbcConversionsConfig;
import ai.vader.server.knowledge.KnowledgeService;
import ai.vader.server.limit.ModelCallLimiter;
import ai.vader.server.llm.JsonEngine;
import ai.vader.server.persistence.SessionRow;
import ai.vader.server.preferences.PreferencesService;
import ai.vader.server.session.TranscriptService;
import ai.vader.server.stt.TranscriptEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

/**
 * The half of the recap that only a real database can answer: whether
 * {@code String[]} survives a round trip through a Postgres {@code text[]}, and
 * whether "generate once" actually holds.
 *
 * <p>The model is a stand-in replaying canned JSON — what is under test is the
 * storage and the call count, not the writing.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    SummaryService.class,
    SessionSummaryStore.class,
    TranscriptService.class,
    KnowledgeService.class,
    PreferencesService.class,
    ModelCallLimiter.class,
    JdbcConversionsConfig.class,
    SessionSummaryStorageTest.Stubs.class
})
@TestPropertySource(properties = "spring.datasource.hikari.connection-timeout=2000")
class SessionSummaryStorageTest {

    /** Counts calls, so "generated once" is a number rather than a belief. */
    static final String DEFAULT_RESPONSE =
            """
            {"summary":"You walked through the payments rewrite.",
             "keyPoints":["They run Postgres on RDS","Hiring at staff level"],
             "actionItems":["Send the architecture doc"]}
            """;

    static class StubJsonEngine implements JsonEngine {
        final List<String> prompts = new ArrayList<>();
        String response = DEFAULT_RESPONSE;

        @Override
        public String complete(List<String> cachedBlocks, String prompt, Map<String, Object> schema) {
            prompts.add(prompt);
            return response;
        }
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        StubJsonEngine jsonEngine() {
            return new StubJsonEngine();
        }

        /** The @DataJdbcTest slice does not autoconfigure Jackson. */
        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SummaryService summaries;

    @Autowired
    private TranscriptService transcripts;

    @Autowired
    private StubJsonEngine llm;

    @Autowired
    private ModelCallLimiter limits;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID alice;
    private UUID bob;
    private UUID sessionId;

    @BeforeEach
    void seed() {
        assumeTrue(databaseReachable(), "local Supabase Postgres is not running");
        // The stub is a context-scoped singleton, so a test that swaps the
        // response would otherwise leak it into whatever JUnit runs next.
        llm.prompts.clear();
        llm.response = DEFAULT_RESPONSE;
        alice = insertAuthUser("alice");
        bob = insertAuthUser("bob");

        SessionRow session = transcripts.openSession(alice);
        sessionId = session.id();
        transcripts.saveTurns(List.of(
                ai.vader.server.persistence.TranscriptTurn.of(
                        sessionId, alice, TranscriptEvent.CHANNEL_INTERVIEWER, "What did you ship?"),
                ai.vader.server.persistence.TranscriptTurn.of(
                        sessionId, alice, TranscriptEvent.CHANNEL_USER, "A payments rewrite.")));
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
    void listsSurviveTheRoundTripThroughAPostgresArray() {
        // The single most likely thing in this feature to need a converter.
        summaries.recapOf(sessionId, alice);
        var reread = summaries.recapOf(sessionId, alice);

        assertThat(reread.keyPoints()).containsExactly("They run Postgres on RDS", "Hiring at staff level");
        assertThat(reread.actionItems()).containsExactly("Send the architecture doc");
        assertThat(reread.summary()).isEqualTo("You walked through the payments rewrite.");
    }

    /**
     * The ordering guard for the limit: the stored-recap early return sits above
     * it, so reopening a recap you already generated never costs a slice of your
     * hour. Getting this the wrong way round bills a user for reading a page.
     */
    @Test
    void aStoredRecapIsServedEvenWhenTheUserIsOverTheirLimit() {
        var generated = summaries.recapOf(sessionId, alice);
        exhaust(alice);

        assertThat(summaries.recapOf(sessionId, alice)).isEqualTo(generated);
        assertThat(llm.prompts).hasSize(1);
    }

    @Test
    void generatingANewRecapOverTheLimitIsRefusedAndCostsNoModelCall() {
        exhaust(alice);

        assertThatThrownBy(() -> summaries.recapOf(sessionId, alice))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(thrown -> ((ResponseStatusException) thrown).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(llm.prompts).isEmpty();
    }

    /** Spends the user's whole hour. Each test seeds a fresh id, so this is local to it. */
    private void exhaust(UUID userId) {
        long now = System.currentTimeMillis();
        for (int call = 0; call <= ModelCallLimiter.MAX_CALLS_PER_HOUR; call++) {
            limits.tryAcquire(userId, now);
        }
    }

    @Test
    void generatesOnceAndServesTheRestFromStorage() {
        summaries.recapOf(sessionId, alice);
        summaries.recapOf(sessionId, alice);
        summaries.recapOf(sessionId, alice);

        assertThat(llm.prompts).hasSize(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from public.session_summaries where session_id = ?", Long.class, sessionId))
                .isEqualTo(1);
    }

    @Test
    void anEmptyListIsStoredAsEmptyRatherThanNull() {
        llm.response = """
                {"summary":"Too short to say anything useful.","keyPoints":[],"actionItems":[]}
                """;

        var recap = summaries.recapOf(sessionId, alice);

        assertThat(recap.keyPoints()).isEmpty();
        assertThat(recap.actionItems()).isEmpty();
    }

    @Test
    void theConversationReachesTheModelWithBothSpeakers() {
        summaries.recapOf(sessionId, alice);

        assertThat(llm.prompts).singleElement().satisfies(prompt -> {
            assertThat(prompt).contains("Interviewer: What did you ship?");
            assertThat(prompt).contains("You: A payments rewrite.");
        });
    }

    @Test
    void refusesToBillForASessionWithNothingInIt() {
        UUID empty = transcripts.openSession(alice).id();

        assertThatThrownBy(() -> summaries.recapOf(empty, alice))
                .isInstanceOf(SummaryService.EmptySessionException.class);
        assertThat(llm.prompts).isEmpty();
    }

    @Test
    void anotherUsersSessionIsNotFoundAndCostsNoModelCall() {
        assertThatThrownBy(() -> summaries.recapOf(sessionId, bob)).isInstanceOf(ResponseStatusException.class);

        assertThat(llm.prompts).isEmpty();
    }

    @Test
    void anotherUserCannotReadAStoredRecapEither() {
        summaries.recapOf(sessionId, alice);

        assertThatThrownBy(() -> summaries.recapOf(sessionId, bob)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void deletingTheSessionTakesTheRecapWithIt() {
        summaries.recapOf(sessionId, alice);

        transcripts.deleteSession(sessionId, alice);

        assertThat(jdbc.queryForObject(
                        "select count(*) from public.session_summaries where session_id = ?", Long.class, sessionId))
                .isZero();
    }
}
