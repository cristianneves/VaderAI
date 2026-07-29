package ai.vader.server.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/** Same rule as everywhere else: the service role bypasses RLS, so every finder takes a userId. */
public interface KnowledgeDocRepository extends CrudRepository<KnowledgeDoc, UUID> {

    List<KnowledgeDoc> findByUserId(UUID userId);

    Optional<KnowledgeDoc> findByUserIdAndKind(UUID userId, KnowledgeKind kind);
}
