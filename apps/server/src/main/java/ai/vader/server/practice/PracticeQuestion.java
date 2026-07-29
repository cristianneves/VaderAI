package ai.vader.server.practice;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One question in a mock interview, and the graded answer to it once given.
 *
 * <p>Question and grade share a row because they are one-to-one and always read
 * together — the report is a straight select over these.
 *
 * <p>{@code userId} is denormalized from the session so every read can be scoped
 * without a join. The backend holds the service role and bypasses RLS, so that
 * scoping is the only thing standing between two users' data.
 */
@Table("practice_questions")
public record PracticeQuestion(
        @Id Long id,
        UUID sessionId,
        UUID userId,
        short position,
        String question,
        String answer,
        Short structure,
        Short specificity,
        Short relevance,
        String feedback,
        String rewrite,
        Instant createdAt,
        Instant gradedAt) {

    static PracticeQuestion asked(UUID sessionId, UUID userId, int position, String question) {
        return new PracticeQuestion(
                null,
                sessionId,
                userId,
                (short) position,
                question,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                null);
    }

    PracticeQuestion graded(String answer, PracticeGrade grade) {
        return new PracticeQuestion(
                id,
                sessionId,
                userId,
                position,
                question,
                answer,
                (short) grade.structure(),
                (short) grade.specificity(),
                (short) grade.relevance(),
                grade.feedback(),
                grade.rewrite(),
                createdAt,
                Instant.now());
    }

    boolean isGraded() {
        return gradedAt != null;
    }
}
