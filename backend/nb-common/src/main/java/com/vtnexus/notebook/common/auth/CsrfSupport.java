package com.vtnexus.notebook.common.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public final class CsrfSupport {

    private CsrfSupport() {
    }

    public static void apply(HttpSecurity http, AuthMode mode) throws Exception {
        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .sessionAuthenticationStrategy(new org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy())
                .ignoringRequestMatchers("/internal/**")
                .ignoringRequestMatchers(CsrfSupport::isBearerRequest));
        http.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
    }

    private static boolean isBearerRequest(HttpServletRequest request) {
        if (hasSessionCookie(request)) {
            return false;
        }
        String auth = request.getHeader("Authorization");
        return auth != null && auth.startsWith("Bearer ");
    }

    private static boolean hasSessionCookie(HttpServletRequest request) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (jakarta.servlet.http.Cookie c : cookies) {
            if (SessionCookieFilter.COOKIE_NAME.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
