package ai.vader.server.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.vader.server.config.JdbcConversionsConfig;
import ai.vader.server.knowledge.KnowledgeKind;
import ai.vader.server.knowledge.KnowledgeService;
import ai.vader.server.limit.ModelCallLimiter;
import ai.vader.server.llm.JsonEngine;
import ai.vader.server.preferences.PreferencesService;
import ai.vader.server.session.TranscriptService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Runs against the local Supabase Postgres for the same reason
 * {@code UserScopingTest} does — the tables hang off Supabase's auth schema, and
 * cross-user isolation is the thing most worth proving.
 *
 * <p>The LLM is a stand-in that replays canned JSON. What is under test is the
 * orchestration and the scoping, not the model.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    PracticeService.class,
    TranscriptService.class,
    KnowledgeService.class,
    // Phase 9b: practice questions and grades come back in the session language.
    PreferencesService.class,
    ModelCallLimiter.class,
    JdbcConversionsConfig.class,
    PracticeServiceTest.Stubs.class
})
@TestPropertySource(properties = "spring.datasource.hikari.connection-timeout=2000")
class PracticeServiceTest {

    /** Replays a queued response per call and records what it was asked. */
    static class StubJsonEngine implements JsonEngine {
        final List<String> prompts = new ArrayList<>();
        final List<String> responses = new ArrayList<>();

        @Override
        public String complete(List<String> cachedBlocks, String prompt, Map<String, Object> schema) {
            prompts.add(prompt);
            if (responses.isEmpty()) throw new IllegalStateException("no stubbed response for: " + prompt);
            return responses.remove(0);
        }
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        StubJsonEngine jsonEngine() {
            return new StubJsonEngine();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private static final String FIVE_QUESTIONS =
            """
            {"questions":["Q one?","Q two?","Q three?","Q four?","Q five?"]}
            """;

    private static final String A_GRADE =
            """
            {"structure":2,"specificity":1,"relevance":4,
             "feedback":"You never named the project or the outcome.",
             "rewrite":"At Acme I cut deploy time from 40 to 6 minutes."}
            """;

    private static final String A_REPORT =
            """
            {"themes":["no concrete numbers","no clear outcome"],
             "summary":"Practise naming one metric per story."}
            """;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PracticeService practice;

    @Autowired
    private TranscriptService transcripts;

    @Autowired
    private KnowledgeService knowledge;

    @Autowired
    private StubJsonEngine llm;

    @Autowired
    private ModelCallLimiter limits;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID alice;
    private UUID bob;

    @BeforeEach
    void seedUsers() {
        assumeTrue(databaseReachable(), "local Supabase Postgres is not running");
        alice = insertAuthUser("alice");
        bob = insertAuthUser("bob");
        llm.responses.clear();
        llm.prompts.clear();
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

    /** A user with a job description on file and a live session socket already open. */
    private UUID readySession(UUID userId) {
        knowledge.save(userId, KnowledgeKind.JOB_DESCRIPTION, null, "staff engineer, payments");
        return transcripts.openSession(userId).id();
    }

    private String kindOf(UUID sessionId) {
        return jdbc.queryForObject("select kind from public.sessions where id = ?", String.class, sessionId);
    }

    @Test
    void generatesAndStoresTheQuestionSet() {
        UUID sessionId = readySession(alice);
        llm.responses.add(FIVE_QUESTIONS);

        List<PracticeQuestion> questions = practice.start(alice, sessionId);

        assertThat(questions).hasSize(5);
        assertThat(questions.get(0).question()).isEqualTo("Q one?");
        assertThat(questions).extracting(PracticeQuestion::position).containsExactly((short) 0, (short)
                1, (short) 2, (short) 3, (short) 4);
        assertThat(questions).allSatisfy(question -> assertThat(question.isGraded()).isFalse());
    }

    @Test
    void reclassifiesTheSessionAsPractice() {
        UUID sessionId = readySession(alice);
        llm.responses.add(FIVE_QUESTIONS);

        assertThat(kindOf(sessionId)).isEqualTo("live");

        practice.start(alice, sessionId);

        // Phase 7's history list needs to tell the two apart without inspecting
        // child rows.
        assertThat(kindOf(sessionId)).isEqualTo("practice");
    }

    @Test
    void startingTwiceReturnsTheSameQuestionsRatherThanASecondSet() {
        UUID sessionId = readySession(alice);
        llm.responses.add(FIVE_QUESTIONS);

        practice.start(alice, sessionId);
        List<PracticeQuestion> again = practice.start(alice, sessionId);

        assertThat(again).hasSize(5);
        // One call, not two: the retry never reached the model.
        assertThat(llm.prompts).hasSize(1);
    }

    @Test
    void refusesToStartOverTheHourlyLimitAndCostsNoModelCall() {
        UUID sessionId = readySession(alice);
        long now = System.currentTimeMillis();
        for (int call = 0; call <= ModelCallLimiter.MAX_CALLS_PER_HOUR; call++) {
            limits.tryAcquire(alice, now);
        }

        assertThatThrownBy(() -> practice.start(alice, sessionId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(thrown -> ((ResponseStatusException) thrown).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(llm.prompts).isEmpty();
    }

    @Test
    void refusesToStartWithoutAJobDescription() {
        UUID sessionId = transcripts.openSession(alice).id();

        assertThatThrownBy(() -> practice.start(alice, sessionId))
                .isInstanceOf(PracticeService.MissingJobDescriptionException.class)
                .hasMessageContaining("job description");
    }

    @Test
    void refusesToStartOnASessionTheUserDoesNotOwn() {
        UUID aliceSession = readySession(alice);
        knowledge.save(bob, KnowledgeKind.JOB_DESCRIPTION, null, "bob's role");

        // Bob knows the session id and asks for it anyway.
        assertThatThrownBy(() -> practice.start(bob, aliceSession))
                .isInstanceOf(PracticeService.UnknownPracticeSessionException.class);
    }

    @Test
    void gradesAnAnswerAndStoresEveryAxis() {
        UUID sessionId = readySession(alice);
        llm.responses.add(FIVE_QUESTIONS);
        practice.start(alice, sessionId);
        llm.responses.add(A_GRADE);

        PracticeQuestion graded = practice.grade(alice, sessionId, 0, "It was fine I guess.");

        assertThat(graded.answer()).isEqualTo("It was fine I guess.");
        assertThat(graded.structure()).isEqualTo((short) 2);
        assertThat(graded.specificity()).isEqualTo((short) 1);
        assertThat(graded.relevance()).isEqualTo((short) 4);
        assertThat(graded.feedback()).contains("never named the project");
        assertThat(graded.rewrite()).contains("40 to 6 minutes");
        assertThat(graded.isGraded()).isTrue();
    }

    @Test
    void gradingSendsTheQuestionAndTheSpokenAnswerToTheModel() {
        UUID sessionId = readySession(alice);
        llm.responses.add(FIVE_QUESTIONS);
        practice.start(alice, sessionId);
        llm.responses.add(A_GRADE);

        practice.grade(alice, sessionId, 2, "um, I mostly just fixed it");

        assertThat(llm.prompts.get(1)).contains("Q three?").contains("um, I mostly just fixed it");
    }

    @Test
    void anotherUserCannotGradeAnswersInSomeoneElsesSession() {
        UUID aliceSession = readySession(alice);
        llm.responses.add(FIVE_QUESTIONS);
        practice.start(alice, aliceSession);

        assertThatThrownBy(() -> practice.grade(bob, aliceSession, 0, "let me in"))
                .isInstanceOf(PracticeService.UnknownPracticeSessionException.class);
    }

    @Test
    void reportsScoresAndWeakestThemes() {
        UUID sessionId = readySession(alice);
        llm.responses.add(FIVE_QUESTIONS);
        practice.start(alice, sessionId);
        llm.responses.add(A_GRADE);
        practice.grade(alice, sessionId, 0, "It was fine I guess.");
        llm.responses.add(A_REPORT);

        PracticeService.Report report = practice.report(alice, sessionId);

        assertThat(report.questions()).hasSize(5);
        assertThat(report.themes()).containsExactly("no concrete numbers", "no clear outcome");
        assertThat(report.summary()).contains("one metric per story");
    }

    @Test
    void reportsNothingBeforeAnyAnswerIsGraded() {
        UUID sessionId = readySession(alice);
        llm.responses.add(FIVE_QUESTIONS);
        practice.start(alice, sessionId);

        PracticeService.Report report = practice.report(alice, sessionId);

        // No graded answers means nothing to summarise, and no call to pay for.
        assertThat(report.questions()).hasSize(5);
        assertThat(report.themes()).isEmpty();
        assertThat(llm.prompts).hasSize(1);
    }

    @Test
    void anotherUserGetsNothingFromTheReport() {
        UUID aliceSession = readySession(alice);
        llm.responses.add(FIVE_QUESTIONS);
        practice.start(alice, aliceSession);

        assertThat(practice.questionsOf(bob, aliceSession)).isEmpty();
        assertThatThrownBy(() -> practice.report(bob, aliceSession))
                .isInstanceOf(PracticeService.UnknownPracticeSessionException.class);
    }
}
