package ai.vader.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.vader.server.config.JdbcConversionsConfig;
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
import org.springframework.test.context.TestPropertySource;

/**
 * The history list, the review read, and the delete cascade.
 *
 * <p>Runs against the local Supabase Postgres because the cascade is the point:
 * an in-memory stand-in would have neither the foreign keys nor the auth schema
 * these tables hang off, so it would prove nothing.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TranscriptService.class, JdbcConversionsConfig.class})
@TestPropertySource(properties = "spring.datasource.hikari.connection-timeout=2000")
class SessionHistoryTest {

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

    /** A session with two turns and one answer, closed. */
    private UUID sessionWithContent(UUID userId) {
        UUID sessionId = transcripts.openSession(userId).id();
        transcripts.saveTurns(List.of(
                TranscriptTurn.of(sessionId, userId, 0, "Tell me about a hard bug."),
                TranscriptTurn.of(sessionId, userId, 1, "Sure, at Acme...")));
        transcripts.saveAnswer(Answer.of(sessionId, userId, "At Acme I cut deploy time.", AnswerTrigger.AUTO));
        transcripts.closeSession(sessionId, userId);
        return sessionId;
    }

    private int countIn(String table, UUID sessionId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from public." + table + " where session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    @Test
    void summarisesASessionWithItsCounts() {
        UUID sessionId = sessionWithContent(alice);

        assertThat(transcripts.summaries(alice)).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(sessionId);
            assertThat(summary.kind()).isEqualTo(SessionKind.LIVE);
            assertThat(summary.turns()).isEqualTo(2);
            assertThat(summary.answers()).isEqualTo(1);
            assertThat(summary.practiceQuestions()).isZero();
            assertThat(summary.endedAt()).isNotNull();
        });
    }

    @Test
    void listsSessionsNewestFirst() {
        UUID older = sessionWithContent(alice);
        UUID newer = sessionWithContent(alice);

        assertThat(transcripts.summaries(alice))
                .extracting(TranscriptService.Summary::id)
                .containsExactly(newer, older);
    }

    @Test
    void includesASessionThatProducedNothing() {
        // An empty session is still worth listing — otherwise a run that failed
        // early would silently vanish rather than showing zero of everything.
        UUID sessionId = transcripts.openSession(alice).id();

        assertThat(transcripts.summaries(alice)).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(sessionId);
            assertThat(summary.turns()).isZero();
            assertThat(summary.answers()).isZero();
        });
    }

    @Test
    void leavesEndedAtNullForASessionThatWasNeverClosed() {
        // closeSession only runs on a clean socket close, so a crash leaves this
        // null and the client has to render it as unfinished.
        transcripts.openSession(alice);

        assertThat(transcripts.summaries(alice))
                .singleElement()
                .satisfies(summary -> assertThat(summary.endedAt()).isNull());
    }

    @Test
    void readsBackTurnsAndAnswersInOrder() {
        UUID sessionId = sessionWithContent(alice);

        assertThat(transcripts.turnsOf(sessionId, alice))
                .extracting(TranscriptTurn::content)
                .containsExactly("Tell me about a hard bug.", "Sure, at Acme...");
        assertThat(transcripts.answersOf(sessionId, alice)).singleElement().satisfies(answer -> {
            assertThat(answer.content()).isEqualTo("At Acme I cut deploy time.");
            assertThat(answer.trigger()).isEqualTo(AnswerTrigger.AUTO);
        });
    }

    @Test
    void deletingASessionCascadesToItsTurnsAndAnswers() {
        UUID sessionId = sessionWithContent(alice);

        assertThat(transcripts.deleteSession(sessionId, alice)).isTrue();

        // Asserted straight against the database: the cascade is the schema's
        // job, and going through the repositories would pass even if it were
        // the service deleting rows one by one.
        assertThat(countIn("transcript_turns", sessionId)).isZero();
        assertThat(countIn("answers", sessionId)).isZero();
        assertThat(transcripts.session(sessionId, alice)).isEmpty();
    }

    @Test
    void deletingAPracticeSessionCascadesToItsQuestions() {
        UUID sessionId = transcripts.openSession(alice).id();
        transcripts.markPractice(sessionId, alice);
        jdbc.update(
                "insert into public.practice_questions (session_id, user_id, position, question) values (?, ?, 0, ?)",
                sessionId,
                alice,
                "Why us?");

        assertThat(transcripts.deleteSession(sessionId, alice)).isTrue();

        assertThat(countIn("practice_questions", sessionId)).isZero();
    }

    @Test
    void countsPracticeQuestionsRatherThanAnswersForAPracticeSession() {
        // A practice run stores its answers on the question rows, so counting
        // `answers` would report a five-question mock interview as empty.
        UUID sessionId = transcripts.openSession(alice).id();
        transcripts.markPractice(sessionId, alice);
        jdbc.update(
                "insert into public.practice_questions (session_id, user_id, position, question) values (?, ?, 0, ?)",
                sessionId,
                alice,
                "Why us?");

        assertThat(transcripts.summaries(alice)).singleElement().satisfies(summary -> {
            assertThat(summary.kind()).isEqualTo(SessionKind.PRACTICE);
            assertThat(summary.practiceQuestions()).isEqualTo(1);
            assertThat(summary.answers()).isZero();
        });
    }

    @Test
    void oneUserNeverSeesAnothersSessions() {
        UUID aliceSession = sessionWithContent(alice);

        assertThat(transcripts.summaries(bob)).isEmpty();
        // Bob knows the session id and asks for it anyway.
        assertThat(transcripts.session(aliceSession, bob)).isEmpty();
        assertThat(transcripts.turnsOf(aliceSession, bob)).isEmpty();
        assertThat(transcripts.answersOf(aliceSession, bob)).isEmpty();
    }

    @Test
    void deletingSomeoneElsesSessionDoesNothing() {
        UUID aliceSession = sessionWithContent(alice);

        assertThat(transcripts.deleteSession(aliceSession, bob)).isFalse();

        assertThat(transcripts.session(aliceSession, alice)).isPresent();
        assertThat(countIn("transcript_turns", aliceSession)).isEqualTo(2);
        assertThat(countIn("answers", aliceSession)).isEqualTo(1);
    }
}
