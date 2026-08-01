package ai.vader.server.config;

import ai.vader.server.protocol.ClientMessage;
import ai.vader.server.session.SessionWebSocketHandler;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final SessionWebSocketHandler handler;

    WebSocketConfig(SessionWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // The client is a desktop app, not a browser origin: in dev it connects
        // from the Vite dev server and in production from file://, which sends
        // Origin: null. Origin checks cannot mean anything here — the security
        // boundary is the first-frame token, not the origin.
        registry.addHandler(handler, "/v1/session").setAllowedOriginPatterns("*");
    }

    /**
     * Binary: one 100 ms audio frame is 6400 bytes, which fits under the 8 KB
     * default with almost no headroom — a longer frame would break the
     * connection with no obvious cause. 64 KB leaves room without being
     * meaningful memory.
     *
     * <p>Text: sized by the largest text frame, which is a screenshot.
     * {@link ClientMessage#MAX_SCREENSHOT_BASE64_CHARS} is 256 Ki chars, plus a
     * note of up to 2000 and an ~80-char envelope, so 320 Ki leaves 64 Ki of
     * headroom. The container enforces this by closing the connection with 1009
     * before the handler ever runs, which is a dead session and no error frame —
     * so the ceiling must stay strictly below this number. Lowering the ceiling
     * without lowering this is safe; the reverse is the bug this sizing exists
     * to prevent.
     *
     * <p>Costs 640 KiB of {@code CharBuffer} per session (a char is two bytes),
     * so 137.5 MiB at fly.toml's hard limit of 200 connections, against a
     * ~768 MiB heap. Acceptable at a concurrency this instance will not reach.
     *
     * <p>Applies to the embedded Tomcat container, so it is unrelated to
     * {@link ServletWebServerFactory} sizing.
     */
    @Bean
    ServletServerContainerFactoryBean createWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(320 * 1024);
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        return container;
    }
}
