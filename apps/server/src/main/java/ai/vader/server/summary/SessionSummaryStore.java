package ai.vader.server.summary;

import java.sql.Array;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code session_summaries} with explicit SQL.
 *
 * <p>Deliberately not a Spring Data aggregate, for two reasons that both come
 * from this table being unusual. {@code session_id} is the primary key and is
 * never null, so {@code CrudRepository.save} would issue an UPDATE against a row
 * that does not exist yet — the aggregate version needed
 * {@code JdbcAggregateTemplate.insert} plus a caught {@code DuplicateKeyException}
 * to work at all. And this is the schema's only array column, so the mapping is
 * the one thing here with no other test coverage in the codebase; going through
 * {@link Array} makes it explicit rather than inherited.
 *
 * <p>The result is one class instead of an entity, a repository and a template,
 * with the insert race handled by {@code on conflict} rather than an exception.
 */
@Repository
class SessionSummaryStore {

    private static final String INSERT =
            """
            insert into session_summaries (session_id, user_id, summary, key_points, action_items)
            values (?, ?, ?, ?, ?)
            on conflict (session_id) do nothing
            """;

    private static final String SELECT =
            """
            select summary, key_points, action_items
            from session_summaries
            where session_id = ? and user_id = ?
            """;

    private final JdbcTemplate jdbc;

    SessionSummaryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SummaryService.Recap> MAPPER = (rs, rowNum) -> new SummaryService.Recap(
            rs.getString("summary"), textArray(rs.getArray("key_points")), textArray(rs.getArray("action_items")));

    private static List<String> textArray(Array column) throws java.sql.SQLException {
        if (column == null) return List.of();
        return List.of((String[]) column.getArray());
    }

    Optional<SummaryService.Recap> find(UUID sessionId, UUID userId) {
        return jdbc.query(SELECT, MAPPER, sessionId, userId).stream().findFirst();
    }

    /**
     * {@code on conflict do nothing} rather than a check-then-insert: two clients
     * opening the recap panel at once both generate, and the primary key decides
     * which write lands.
     */
    void insert(UUID sessionId, UUID userId, SummaryService.Recap recap) {
        jdbc.update(INSERT, statement -> {
            statement.setObject(1, sessionId);
            statement.setObject(2, userId);
            statement.setString(3, recap.summary());
            statement.setArray(
                    4, statement.getConnection().createArrayOf("text", recap.keyPoints().toArray()));
            statement.setArray(
                    5,
                    statement.getConnection().createArrayOf("text", recap.actionItems().toArray()));
        });
    }
}
