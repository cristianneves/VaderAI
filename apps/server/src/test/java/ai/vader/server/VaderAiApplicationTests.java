package ai.vader.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VaderAiApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
        // Fails if any bean wiring is broken.
    }

    @Test
    void healthEndpointReportsUp() {
        String body = rest.getForObject("http://localhost:" + port + "/actuator/health", String.class);
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void sessionHistoryRequiresABearerToken() {
        // /v1/session (singular) is permitAll because the WebSocket authenticates
        // itself with its first frame. The history routes are one character away
        // from that, so this pins the difference rather than trusting that
        // Spring's exact-path matcher keeps behaving.
        var response = rest.getForEntity("http://localhost:" + port + "/v1/sessions", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
