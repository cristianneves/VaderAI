package ai.vader.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import ai.vader.server.session.TranscriptService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * What a failure looks like from the outside, over a real HTTP request.
 *
 * <p>The negative is the one that matters: whatever the exception said stays in
 * the log. Before this advice existed an unhandled failure fell through to
 * Spring's default error body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiExceptionHandlerTest {

    /** Stands in for the kind of thing a leaked exception message gives away. */
    private static final String LEAK = "relation \"sessions\" does not exist";

    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private TranscriptService transcripts;

    @BeforeEach
    void acceptAnyToken() {
        given(jwtDecoder.decode(any()))
                .willReturn(Jwt.withTokenValue("token")
                        .header("alg", "ES256")
                        .subject(UUID.randomUUID().toString())
                        .build());
    }

    private ResponseEntity<String> get(String path) {
        var headers = new HttpHeaders();
        headers.setBearerAuth("token");
        return rest.exchange(
                "http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void reportsAnUnhandledFailureWithoutRepeatingWhatItSaid() {
        given(transcripts.summaries(any())).willThrow(new IllegalStateException(LEAK));

        var response = get("/v1/sessions");

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody()).contains("Something went wrong on the server.").doesNotContain(LEAK);
    }

    /**
     * The catch-all above matches RuntimeException, so without a handler of its
     * own a deliberate 404 would come back as a 500.
     */
    @Test
    void keepsTheStatusAndMessageAControllerChoseOnPurpose() {
        given(transcripts.session(any(), any())).willReturn(Optional.empty());

        var response = get("/v1/sessions/" + SESSION_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody()).contains("no session " + SESSION_ID);
    }
}
