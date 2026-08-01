package ai.vader.server.limit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP shape of the usage read, and that it is scoped to the token rather
 * than to anything the caller sends.
 *
 * <p>{@code SecurityConfig} is package-private and is deliberately not imported
 * here — this runs against Boot's default test chain, so it proves the endpoint
 * requires authentication but not that our own rules are the reason. The rules
 * themselves are covered where they matter, in the WebSocket handshake tests.
 */
@WebMvcTest(controllers = UsageController.class)
class UsageControllerTest {

    private static final UUID USER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ModelCallLimiter limits;

    @Test
    void reportsWhatIsLeftAgainstTheCap() throws Exception {
        given(limits.remaining(eq(USER), anyLong())).willReturn(7);

        mvc.perform(get("/v1/usage").with(jwt().jwt(token -> token.subject(USER.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(7))
                .andExpect(jsonPath("$.limit").value(ModelCallLimiter.MAX_CALLS_PER_HOUR));
    }

    @Test
    void refusesAnUnauthenticatedRead() throws Exception {
        mvc.perform(get("/v1/usage")).andExpect(status().isUnauthorized());
    }

    @Test
    void readsTheUserFromTheTokenRatherThanTheRequest() throws Exception {
        given(limits.remaining(any(), anyLong())).willReturn(0);

        mvc.perform(get("/v1/usage?userId=" + UUID.randomUUID())
                        .with(jwt().jwt(token -> token.subject(USER.toString()))))
                .andExpect(status().isOk());

        // The query parameter is ignored entirely — the subject is the only
        // identity this endpoint knows about.
        verify(limits).remaining(eq(USER), anyLong());
    }
}
