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

    public SecurityConfig(
            AuthProperties authProperties,
            BearerDelegationAuthenticator bearerAuthenticator,
            InternalSessionClient internalSessionClient,
            ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.bearerAuthenticator = bearerAuthenticator;
        this.internalSessionClient = internalSessionClient;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        AuthMode mode = AuthMode.fromString(authProperties.getMode());

        Filter authFilter;
        if (mode == AuthMode.NO_OIDC || mode == AuthMode.LDAP) {
            authFilter = new SessionCookieFilter(internalSessionClient, objectMapper);
        } else {
            authFilter = new BearerFilter(bearerAuthenticator, objectMapper);
        }

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
