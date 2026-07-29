package ai.vader.server.knowledge;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("knowledge_docs")
public record KnowledgeDoc(
        @Id UUID id,
        UUID userId,
        KnowledgeKind kind,
        String title,
        String content,
        Instant createdAt,
        Instant updatedAt) {

    static KnowledgeDoc created(UUID userId, KnowledgeKind kind, String title, String content) {
        Instant now = Instant.now();
        return new KnowledgeDoc(null, userId, kind, title, content, now, now);
    }

    KnowledgeDoc withContent(String newTitle, String newContent) {
        return new KnowledgeDoc(id, userId, kind, newTitle, newContent, createdAt, Instant.now());
    }
}
