package ai.vader.server.knowledge;

import ai.vader.server.llm.TokenCounter;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The settings screen's API. Every method takes the user id from the verified
 * JWT — never from the request body — so one user cannot address another's
 * documents.
 */
@RestController
@RequestMapping("/v1/knowledge")
class KnowledgeController {

    private final KnowledgeService knowledge;
    private final DocumentTextExtractor extractor;
    private final TokenCounter tokens;

    KnowledgeController(KnowledgeService knowledge, DocumentTextExtractor extractor, TokenCounter tokens) {
        this.knowledge = knowledge;
        this.extractor = extractor;
        this.tokens = tokens;
    }

    record DocumentView(KnowledgeKind kind, String title, String content) {}

    record KnowledgeView(List<DocumentView> documents, long tokens, boolean estimated, long ceiling) {}

    record SaveRequest(String title, @NotNull String content) {}

    private static UUID userIdOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping
    KnowledgeView list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = userIdOf(jwt);
        var documents = knowledge.list(userId).stream()
                .map(doc -> new DocumentView(doc.kind(), doc.title(), doc.content()))
                .toList();
        // Counted on the assembled block, because that is what actually reaches
        // the prompt.
        var count = tokens.count(knowledge.assemble(userId));
        return new KnowledgeView(documents, count.tokens(), count.estimated(), KnowledgeService.TOKEN_CEILING);
    }

    @PutMapping("/{kind}")
    ResponseEntity<Void> save(
            @AuthenticationPrincipal Jwt jwt, @PathVariable KnowledgeKind kind, @RequestBody SaveRequest request) {
        knowledge.save(userIdOf(jwt), kind, request.title(), request.content());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{kind}/file")
    DocumentView upload(
            @AuthenticationPrincipal Jwt jwt, @PathVariable KnowledgeKind kind, @RequestParam MultipartFile file)
            throws IOException {
        String content = extractor.extract(file.getContentType(), file.getBytes());
        var saved = knowledge.save(userIdOf(jwt), kind, file.getOriginalFilename(), content);
        return new DocumentView(saved.kind(), saved.title(), saved.content());
    }

    @DeleteMapping("/{kind}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable KnowledgeKind kind) {
        knowledge.delete(userIdOf(jwt), kind);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(DocumentTextExtractor.UnsupportedDocumentException.class)
    ResponseEntity<String> onUnsupportedDocument(DocumentTextExtractor.UnsupportedDocumentException failed) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(failed.getMessage());
    }
}
