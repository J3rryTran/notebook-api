package com.vtnexus.notebook.execution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.auth.*;
import com.vtnexus.notebook.execution.client.InternalSessionClient;
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
    private final BearerDelegationAuthenticator bearerAuthenticator;
    private final InternalSessionClient internalSessionClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public SecurityConfig(
            AuthProperties authProperties,
            BearerDelegationAuthenticator bearerAuthenticator,
            InternalSessionClient internalSessionClient,
            ObjectMapper objectMapper,
            com.vtnexus.notebook.common.config.NotebookProperties notebookProperties) {
        this.authProperties = authProperties;
        this.bearerAuthenticator = bearerAuthenticator;
        this.internalSessionClient = internalSessionClient;
        this.objectMapper = objectMapper;
        this.internalToken = notebookProperties.getInternalToken();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        AuthMode mode = AuthMode.fromString(authProperties.getMode());

        if (mode == null) {
            throw new IllegalStateException("Unsupported or missing auth mode: " + authProperties.getMode());
        }

        Filter authFilter = switch (mode) {
            case NO_OIDC, LDAP -> new SessionCookieFilter(internalSessionClient, objectMapper);
            case OIDC -> new BearerFilter(bearerAuthenticator, objectMapper);
        };

        CsrfSupport.apply(http, mode);

        http
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new InternalTokenFilter(internalToken, objectMapper), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
