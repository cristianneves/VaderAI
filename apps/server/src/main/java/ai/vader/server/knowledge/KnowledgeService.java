package ai.vader.server.knowledge;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads and writes the user's background, and assembles it into the block that
 * goes in the cached prompt prefix.
 */
@Service
public class KnowledgeService {

    /** Beyond this the prefix costs more than it is worth and crowds the transcript. */
    public static final long TOKEN_CEILING = 8_000;

    private final KnowledgeDocRepository docs;

    KnowledgeService(KnowledgeDocRepository docs) {
        this.docs = docs;
    }

    public List<KnowledgeDoc> list(UUID userId) {
        return docs.findByUserId(userId).stream()
                .sorted(Comparator.comparing(KnowledgeDoc::kind))
                .toList();
    }

    /** One document per kind: writing a slot replaces whatever was in it. */
    public KnowledgeDoc save(UUID userId, KnowledgeKind kind, String title, String content) {
        return docs.save(docs.findByUserIdAndKind(userId, kind)
                .map(existing -> existing.withContent(title, content))
                .orElseGet(() -> KnowledgeDoc.created(userId, kind, title, content)));
    }

    public void delete(UUID userId, KnowledgeKind kind) {
        docs.findByUserIdAndKind(userId, kind).ifPresent(docs::delete);
    }

    /**
     * The knowledge base as it appears in the prompt. Sections are ordered by
     * {@link KnowledgeKind} rather than by insertion or database order — this
     * string is a cache key in all but name, and an unstable order would mean a
     * cold cache on every session.
     */
    public String assemble(UUID userId) {
        var text = new StringBuilder();
        for (KnowledgeDoc doc : list(userId)) {
            if (doc.content() == null || doc.content().isBlank()) continue;
            if (!text.isEmpty()) text.append("\n\n");
            text.append(doc.kind().heading()).append(":\n").append(doc.content().strip());
        }
        return text.toString();
    }
}
