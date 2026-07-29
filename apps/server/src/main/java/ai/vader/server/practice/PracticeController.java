package ai.vader.server.practice;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Practice mode's API. The session id comes from the {@code ready} frame the
 * socket already sent the client, so no session is created here.
 *
 * <p>Every method takes the user id from the verified JWT — never from the path
 * or body — so one user cannot address another's mock interview.
 */
@RestController
@RequestMapping("/v1/practice")
class PracticeController {

    private final PracticeService practice;

    PracticeController(PracticeService practice) {
        this.practice = practice;
    }

    /** Nulls until the answer is graded, which is how the client knows what to render. */
    record QuestionView(
            int position,
            String question,
            String answer,
            Short structure,
            Short specificity,
            Short relevance,
            String feedback,
            String rewrite) {

        static QuestionView of(PracticeQuestion question) {
            return new QuestionView(
                    question.position(),
                    question.question(),
                    question.answer(),
                    question.structure(),
                    question.specificity(),
                    question.relevance(),
                    question.feedback(),
                    question.rewrite());
        }
    }

    record QuestionSetView(List<QuestionView> questions) {}

    record ReportView(List<QuestionView> questions, List<String> themes, String summary) {}

    record GradeRequest(@NotNull String answer) {}

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static QuestionSetView viewOf(List<PracticeQuestion> questions) {
        return new QuestionSetView(questions.stream().map(QuestionView::of).toList());
    }

    @PostMapping("/{sessionId}")
    QuestionSetView start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return viewOf(practice.start(userIdOf(jwt), sessionId));
    }

    @GetMapping("/{sessionId}")
    QuestionSetView questions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return viewOf(practice.questionsOf(userIdOf(jwt), sessionId));
    }

    @PostMapping("/{sessionId}/{position}")
    QuestionView grade(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sessionId,
            @PathVariable int position,
            @RequestBody GradeRequest request) {
        return QuestionView.of(practice.grade(userIdOf(jwt), sessionId, position, request.answer()));
    }

    @GetMapping("/{sessionId}/report")
    ReportView report(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        var report = practice.report(userIdOf(jwt), sessionId);
        return new ReportView(
                report.questions().stream().map(QuestionView::of).toList(), report.themes(), report.summary());
    }

    /** Actionable rather than a bare 409 — this one is the user's to fix, in Settings. */
    @ExceptionHandler(PracticeService.MissingJobDescriptionException.class)
    ResponseEntity<String> onMissingJobDescription(PracticeService.MissingJobDescriptionException failed) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(failed.getMessage());
    }

    @ExceptionHandler(PracticeService.UnknownPracticeSessionException.class)
    ResponseEntity<String> onUnknownSession(PracticeService.UnknownPracticeSessionException failed) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(failed.getMessage());
    }
}
