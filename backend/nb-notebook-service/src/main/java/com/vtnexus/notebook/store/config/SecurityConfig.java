package com.vtnexus.notebook.store.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.auth.*;
import com.vtnexus.notebook.store.service.AuthSessionService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AuthProperties authProperties;
    private final OidcTokenVerifiers oidcTokenVerifiers;
    private final AuthSessionService sessionService;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public SecurityConfig(
            AuthProperties authProperties,
            OidcTokenVerifiers oidcTokenVerifiers,
            AuthSessionService sessionService,
            ObjectMapper objectMapper,
            com.vtnexus.notebook.common.config.NotebookProperties notebookProperties) {
        this.authProperties = authProperties;
        this.oidcTokenVerifiers = oidcTokenVerifiers;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
        this.internalToken = notebookProperties.getInternalToken();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        AuthMode mode = AuthMode.fromString(authProperties.getMode());

        if (mode == null) {
            throw new IllegalStateException("Unsupported or missing auth mode: " + authProperties.getMode());
        }

        Filter authFilter = new SessionCookieFilter(sessionService::resolveSession, objectMapper,
                mode == AuthMode.OIDC ? oidcTokenVerifiers : null);

        CsrfSupport.apply(http, mode);

        http
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/**", "/internal/**", "/api/v1/auth/config").permitAll()
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/oidc/login", "/api/v1/auth/callback").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new InternalTokenFilter(internalToken, objectMapper), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
