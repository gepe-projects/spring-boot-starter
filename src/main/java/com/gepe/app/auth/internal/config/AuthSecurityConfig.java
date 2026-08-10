package com.gepe.app.auth.internal.config;

import com.gepe.app.platform.web.api.ApiVersions;
import com.gepe.app.platform.web.response.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class AuthSecurityConfig {

    private final ObjectMapper objectMapper;

    AuthSecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // ──────────────────────────────────────────────
    // Chain 0: health probes → public
    // ──────────────────────────────────────────────
    @Bean
    @Order(0)
    SecurityFilterChain healthProbe(HttpSecurity http) throws Exception {
        http.securityMatchers(m -> m.requestMatchers(HttpMethod.GET,
                "/actuator/health", "/actuator/health/**"));
        http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    // ──────────────────────────────────────────────
    // Chain 1: /.well-known/jwks.json → public
    // ──────────────────────────────────────────────
    @Bean
    @Order(1)
    SecurityFilterChain wellKnown(HttpSecurity http) throws Exception {
        http.securityMatchers(m -> m.requestMatchers("/.well-known/jwks.json"));
        http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    // ──────────────────────────────────────────────
    // Chain 2: public auth routes → no JWT
    // ──────────────────────────────────────────────
    @Bean
    @Order(2)
    SecurityFilterChain publicAuth(HttpSecurity http) throws Exception {
        String prefix = "/api/" + ApiVersions.CURRENT + "/auth";
        http.securityMatchers(m -> m.requestMatchers(
                prefix + "/register",
                prefix + "/login",
                prefix + "/refresh",
                prefix + "/logout",
                prefix + "/oauth/**"
        ));
        http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    // ──────────────────────────────────────────────
    // Chain 3: /api/** → Bearer JWT
    // ──────────────────────────────────────────────
    @Bean
    @Order(3)
    SecurityFilterChain api(HttpSecurity http, JwtDecoder jwtDecoder,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthConverter) throws Exception {
        http.securityMatcher("/api/**");
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/api/" + ApiVersions.CURRENT + "/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated());
        http.oauth2ResourceServer(o -> o
                .jwt(j -> j
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthConverter))
                .authenticationEntryPoint((request, response, authException) ->
                        writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                "Authentication required")));
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.exceptionHandling(e -> e
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                                "Access denied")));
        return http.build();
    }

    // ──────────────────────────────────────────────
    // Chain 4: Google OAuth callback → public
    // ──────────────────────────────────────────────
    @Bean
    @Order(4)
    SecurityFilterChain oauthCallback(HttpSecurity http) throws Exception {
        http.securityMatchers(m -> m.requestMatchers(HttpMethod.GET, "/auth/oauth/google/callback"));
        http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    // ──────────────────────────────────────────────
    // Chain LOWEST: catch-all → deny
    // ──────────────────────────────────────────────
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain fallback(HttpSecurity http) throws Exception {
        http.securityMatcher("/**");
        http.authorizeHttpRequests(a -> a.anyRequest().denyAll());
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, authException) ->
                        writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                "Not found"))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                                "Access denied")));
        return http.build();
    }

    private void writeJsonError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(status);
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(message, null));
    }
}
