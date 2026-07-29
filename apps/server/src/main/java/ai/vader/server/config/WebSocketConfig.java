package ai.vader.server.config;

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
     * One 100 ms audio frame is 6400 bytes, which fits under the 8 KB default
     * with almost no headroom — a longer frame would break the connection with
     * no obvious cause. 64 KB leaves room without being meaningful memory.
     *
     * <p>Applies to the embedded Tomcat container, so it is unrelated to
     * {@link ServletWebServerFactory} sizing.
     */
    @Bean
    ServletServerContainerFactoryBean createWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        return container;
    }
}
