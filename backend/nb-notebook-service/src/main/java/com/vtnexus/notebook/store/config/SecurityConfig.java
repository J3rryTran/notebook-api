package com.vtnexus.notebook.store.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.auth.*;
import com.vtnexus.notebook.store.service.AuthSessionService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AuthProperties authProperties;
    private final BearerDelegationAuthenticator bearerAuthenticator;
    private final AuthSessionService sessionService;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public SecurityConfig(
            AuthProperties authProperties,
            BearerDelegationAuthenticator bearerAuthenticator,
            AuthSessionService sessionService,
            ObjectMapper objectMapper,
            @Value("${notebook.internalToken:${notebook.internal-token:}}") String internalToken) {
        this.authProperties = authProperties;
        this.bearerAuthenticator = bearerAuthenticator;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        AuthMode mode = AuthMode.fromString(authProperties.getMode());

        if (mode == null) {
            throw new IllegalStateException("Unsupported or missing auth mode: " + authProperties.getMode());
        }

        Filter authFilter = switch (mode) {
            case NO_OIDC, LDAP -> new SessionCookieFilter(sessionService::resolveSession, objectMapper);
            case OIDC -> new BearerFilter(bearerAuthenticator, objectMapper);
        };

        if (mode == AuthMode.OIDC) {
            // Mode OIDC dùng Bearer token, không dùng session cookie nên tắt CSRF (B2)
            http.csrf(csrf -> csrf.disable());
        } else {
            // Mode NO_OIDC & LDAP: CSRF bật qua CookieCsrfTokenRepository và CsrfTokenRequestAttributeHandler (B2)
            http.csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers("/internal/**")
            );
            // Nạp token lười để cookie XSRF-TOKEN luôn được gửi về
            http.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
        }

        http
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/internal/**", "/api/v1/auth/config").permitAll()
                // /api/v1/auth/login được permitAll nhưng vẫn qua CSRF filter (nhận cookie từ /config trước)
                .requestMatchers("/api/v1/auth/login").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new InternalTokenFilter(internalToken, objectMapper), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Filter đảm bảo CsrfToken được đọc để set cookie XSRF-TOKEN cho client (B2)
     */
    private static class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                // Ép buộc nạp token lười
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
