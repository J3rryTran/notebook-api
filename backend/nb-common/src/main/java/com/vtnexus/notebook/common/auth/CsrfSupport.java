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

/**
 * Cấu hình CSRF dùng chung cho notebook-service và execution-service (B2, B10).
 * <p>
 * OIDC: xác thực bằng Bearer, không có cookie phiên → tắt CSRF.
 * NO_OIDC / LDAP: cookie {@code NB_SESSION} → double-submit qua cookie {@code XSRF-TOKEN} (path /) và header
 * {@code X-XSRF-TOKEN}. Hai service đứng sau cùng origin nginx nên dùng chung cookie này.
 */
public final class CsrfSupport {

    private CsrfSupport() {
    }

    public static void apply(HttpSecurity http, AuthMode mode) throws Exception {
        if (mode == AuthMode.OIDC) {
            http.csrf(csrf -> csrf.disable());
            return;
        }
        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/internal/**"));
        http.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
    }

    /** Spring Security 6 nạp token lười; đọc token để cookie XSRF-TOKEN luôn được ghi về client. */
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
