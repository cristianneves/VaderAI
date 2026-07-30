package ai.vader.server.preferences;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * Reads and writes the one column we own on {@code profiles}.
 *
 * <p>Deliberately not a {@code CrudRepository}: a whole-row {@code save} would
 * make us responsible for {@code email} and {@code created_at}, which are the
 * auth trigger's to maintain, and a stale read would quietly overwrite them.
 */
interface ProfileRepository extends Repository<Profile, UUID> {

    @Query("select language from profiles where id = :userId")
    Optional<String> findLanguage(UUID userId);

    @Modifying
    @Query("update profiles set language = :language where id = :userId")
    int updateLanguage(UUID userId, String language);
}
