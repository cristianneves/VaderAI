package ai.vader.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.vader.server.session.TranscriptService;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The backend connects with the service role, which bypasses RLS. Nothing in the
 * database stops it reading another user's rows — only the userId argument
 * threaded through every repository call does. This is the test that proves it.
 *
 * <p>Runs against the local Supabase Postgres because the schema depends on
 * Supabase's own {@code auth.users} table; it is skipped when the stack is not
 * running (`pnpm supabase:start`) rather than testing a stand-in schema that
 * would not catch a real mistake.
 */
@DataJdbcTest
// Keep the configured datasource: an in-memory stand-in would not have the
// Supabase auth schema these tables hang off, so it would prove nothing.
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ai.vader.server.session.TranscriptService.class)
// Fail fast rather than sitting on the default 30 s connect timeout where the
// stack is not running, e.g. CI.
@org.springframework.test.context.TestPropertySource(
        properties = "spring.datasource.hikari.connection-timeout=2000")
class UserScopingTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TranscriptService transcripts;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID alice;
    private UUID bob;

    @BeforeEach
    void seedUsers() {
        assumeTrue(databaseReachable(), "local Supabase Postgres is not running");
        alice = insertAuthUser("alice@example.com");
        bob = insertAuthUser("bob@example.com");
    }

    private boolean databaseReachable() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception unreachable) {
            return false;
        }
    }

    private UUID insertAuthUser(String email) {
        UUID id = UUID.randomUUID();
        // Mirrors what GoTrue writes on sign-up; the trigger fills in profiles.
        jdbc.update(
                """
                insert into auth.users (id, instance_id, aud, role, email, encrypted_password,
                                        email_confirmed_at, created_at, updated_at)
                values (?, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
                        ?, '', now(), now(), now())
                """,
                id,
                id + email);
        return id;
    }

    @Test
    void aUserReadsOnlyTheirOwnTurns() {
        UUID aliceSession = transcripts.openSession(alice).id();
        UUID bobSession = transcripts.openSession(bob).id();
        transcripts.saveTurns(List.of(TranscriptTurn.of(aliceSession, alice, 0, "alice's interviewer")));
        transcripts.saveTurns(List.of(TranscriptTurn.of(bobSession, bob, 1, "bob's answer")));

        assertThat(transcripts.turnsOf(aliceSession, alice))
                .extracting(TranscriptTurn::content)
                .containsExactly("alice's interviewer");
        assertThat(transcripts.turnsOf(bobSession, bob))
                .extracting(TranscriptTurn::content)
                .containsExactly("bob's answer");
    }

    @Test
    void readingAnotherUsersSessionReturnsNothing() {
        UUID aliceSession = transcripts.openSession(alice).id();
        transcripts.saveTurns(List.of(TranscriptTurn.of(aliceSession, alice, 0, "confidential")));

        // Bob knows the session id and asks for it anyway.
        assertThat(transcripts.turnsOf(aliceSession, bob)).isEmpty();
        assertThat(transcripts.session(aliceSession, bob)).isEmpty();
    }

    @Test
    void openSessionAssignsAGeneratedIdAndTheOwningUser() {
        SessionRow row = transcripts.openSession(alice);

        assertThat(row.id()).isNotNull();
        assertThat(row.userId()).isEqualTo(alice);
        assertThat(transcripts.session(row.id(), alice)).isPresent();
    }

    @Test
    void closingASessionOnlyWorksForItsOwner() {
        UUID aliceSession = transcripts.openSession(alice).id();

        transcripts.closeSession(aliceSession, bob);
        assertThat(transcripts.session(aliceSession, alice).orElseThrow().endedAt()).isNull();

        transcripts.closeSession(aliceSession, alice);
        assertThat(transcripts.session(aliceSession, alice).orElseThrow().endedAt()).isNotNull();
    }

    @Test
    void turnsAreWrittenInBatches() {
        UUID session = transcripts.openSession(alice).id();
        var batch = List.of(
                TranscriptTurn.of(session, alice, 0, "one"),
                TranscriptTurn.of(session, alice, 1, "two"),
                TranscriptTurn.of(session, alice, 0, "three"));

        transcripts.saveTurns(batch);

        assertThat(transcripts.turnsOf(session, alice))
                .extracting(TranscriptTurn::content)
                .containsExactly("one", "two", "three");
    }
}
