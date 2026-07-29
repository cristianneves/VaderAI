package ai.vader.server.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
class SecurityConfig {

    /**
     * The desktop app is not a browser origin: in dev it calls from the Vite dev
     * server and in production from {@code file://}, which sends
     * {@code Origin: null}. Requests are authenticated by bearer token and carry
     * no cookies, so the origin is not a security boundary here — without this,
     * the settings screen's calls fail CORS preflight and appear to hang.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        var cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(List.of("*"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // The WebSocket handshake cannot carry an Authorization
                        // header, so it is authenticated by its first frame
                        // instead — see SessionWebSocketHandler.
                        .requestMatchers("/v1/session").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }
}
