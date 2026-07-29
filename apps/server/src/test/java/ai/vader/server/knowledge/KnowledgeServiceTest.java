package ai.vader.server.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import ai.vader.server.config.JdbcConversionsConfig;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs against the local Supabase Postgres for the same reason
 * {@code UserScopingTest} does — the table hangs off Supabase's auth schema, and
 * the one-document-per-kind rule is enforced by a database index.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({KnowledgeService.class, JdbcConversionsConfig.class})
@TestPropertySource(properties = "spring.datasource.hikari.connection-timeout=2000")
class KnowledgeServiceTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private KnowledgeService knowledge;

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
    void savesAndReadsBackADocument() {
        knowledge.save(alice, KnowledgeKind.RESUME, "cv.pdf", "Cut deploy time from 40 to 6 minutes.");

        assertThat(knowledge.list(alice))
                .singleElement()
                .satisfies(doc -> {
                    assertThat(doc.kind()).isEqualTo(KnowledgeKind.RESUME);
                    assertThat(doc.title()).isEqualTo("cv.pdf");
                    assertThat(doc.content()).contains("40 to 6 minutes");
                });
    }

    @Test
    void savingTheSameKindTwiceReplacesItRatherThanAddingOne() {
        knowledge.save(alice, KnowledgeKind.RESUME, "old.pdf", "first version");
        knowledge.save(alice, KnowledgeKind.RESUME, "new.pdf", "second version");

        assertThat(knowledge.list(alice)).singleElement().satisfies(doc -> assertThat(doc.content())
                .isEqualTo("second version"));
    }

    @Test
    void oneUserNeverSeesAnothersDocuments() {
        knowledge.save(alice, KnowledgeKind.RESUME, "cv.pdf", "alice's confidential résumé");

        assertThat(knowledge.list(bob)).isEmpty();
        assertThat(knowledge.assemble(bob)).isEmpty();
    }

    @Test
    void deletingOnlyAffectsTheOwner() {
        knowledge.save(alice, KnowledgeKind.RESUME, "cv.pdf", "alice's résumé");

        knowledge.delete(bob, KnowledgeKind.RESUME);

        assertThat(knowledge.list(alice)).hasSize(1);
    }

    @Test
    void assemblesSectionsInAFixedOrderRegardlessOfWriteOrder() {
        // The assembled text is the cached prompt prefix; an order that depended
        // on insertion or row order would mean a cold cache every session.
        knowledge.save(alice, KnowledgeKind.NOTES, null, "prefers backend work");
        knowledge.save(alice, KnowledgeKind.RESUME, null, "ten years of Java");
        knowledge.save(alice, KnowledgeKind.JOB_DESCRIPTION, null, "staff engineer, payments");

        String assembled = knowledge.assemble(alice);

        assertThat(assembled.indexOf("Résumé")).isLessThan(assembled.indexOf("Job description"));
        assertThat(assembled.indexOf("Job description")).isLessThan(assembled.indexOf("Notes"));
    }

    @Test
    void assemblyIsByteStableAcrossCalls() {
        knowledge.save(alice, KnowledgeKind.RESUME, null, "ten years of Java");
        knowledge.save(alice, KnowledgeKind.NOTES, null, "prefers backend work");

        assertThat(knowledge.assemble(alice)).isEqualTo(knowledge.assemble(alice));
    }

    @Test
    void skipsBlankDocuments() {
        knowledge.save(alice, KnowledgeKind.RESUME, null, "ten years of Java");
        knowledge.save(alice, KnowledgeKind.NOTES, null, "   ");

        assertThat(knowledge.assemble(alice)).doesNotContain("Notes");
    }

    @Test
    void anEditChangesTheAssembledPrefix() {
        // Which is the point: a new prefix means one cold cache write, then reads.
        knowledge.save(alice, KnowledgeKind.RESUME, null, "first version");
        String before = knowledge.assemble(alice);

        knowledge.save(alice, KnowledgeKind.RESUME, null, "second version");

        assertThat(knowledge.assemble(alice)).isNotEqualTo(before);
    }
}
