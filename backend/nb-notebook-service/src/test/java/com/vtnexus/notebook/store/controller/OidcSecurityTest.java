package com.vtnexus.notebook.store.controller;

import com.vtnexus.notebook.common.auth.OidcTokenVerifiers;
import com.vtnexus.notebook.common.auth.SessionCookieFilter;
import com.vtnexus.notebook.common.auth.SessionInfo;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.model.NotebookAuthSession;
import com.vtnexus.notebook.store.service.AuthSessionService;
import com.vtnexus.notebook.store.service.NotebookService;
import com.vtnexus.notebook.store.service.OidcLoginService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "notebook.auth.mode=oidc",
        "notebook.encryptionKey=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=",
        "notebook.internalToken=secret-token-12345",
        "notebook.auth.oidc.jwt.issuer=https://kc.test/realms/r",
        "notebook.auth.oidc.jwt.audience=kyuubi",
        "notebook.auth.oidc.jwt.jwks-url=http://kc.test/certs",
        "notebook.auth.oidc.ui.client-secret=s",
        "notebook.auth.oidc.authorization-endpoint=https://kc.test/auth",
        "notebook.auth.oidc.token-endpoint=http://kc.test/token",
        "spring.datasource.url=jdbc:mariadb://localhost:3306/dummy",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDBDialect"
})
class OidcSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthSessionService sessionService;

    @MockBean
    private OidcLoginService oidcLoginService;

    @MockBean
    private OidcTokenVerifiers oidcTokenVerifiers;

    @MockBean
    private NotebookService notebookService;

    @Test
    void configAdvertisesServerSideLogin() throws Exception {
        mockMvc.perform(get("/api/v1/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("oidc"))
                .andExpect(jsonPath("$.loginUrl").value("/api/v1/auth/oidc/login"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void loginEndpointRedirectsWithoutSession() throws Exception {
        when(oidcLoginService.buildAuthorizationRedirect(any(), eq("/x"))).thenReturn("https://kc.test/auth?state=s1");
        mockMvc.perform(get("/api/v1/auth/oidc/login").param("redirect", "/x"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://kc.test/auth?state=s1"));
    }

    @Test
    void callbackSetsLaxHttpOnlySessionCookieAndRedirects() throws Exception {
        NotebookAuthSession session = NotebookAuthSession.builder().id("sid-1").username("alice").authMode("oidc").build();
        when(oidcLoginService.completeLogin("c1", "s1")).thenReturn(new OidcLoginService.LoginResult(session, "/nb"));

        MvcResult r = mockMvc.perform(get("/api/v1/auth/callback").param("code", "c1").param("state", "s1"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/nb"))
                .andReturn();
        String setCookie = r.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(SessionCookieFilter.COOKIE_NAME + "=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("NB_SESSION=sid-1").contains("HttpOnly").contains("SameSite=Lax");
    }

    @Test
    void callbackFailureRedirectsHomeWithErrorAndNoCookie() throws Exception {
        when(oidcLoginService.completeLogin("c1", "bad"))
                .thenThrow(new NotebookApiException("OIDC_LOGIN_FAILED", "Unknown state", HttpStatus.UNAUTHORIZED));
        MvcResult r = mockMvc.perform(get("/api/v1/auth/callback").param("code", "c1").param("state", "bad"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/?authError=oidc_login_failed"))
                .andReturn();
        assertThat(r.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(h -> h.startsWith("NB_SESSION="));

        mockMvc.perform(get("/api/v1/auth/callback").param("error", "access_denied"))
                .andExpect(header().string(HttpHeaders.LOCATION, "/?authError=access_denied"));
        verify(oidcLoginService, never()).completeLogin(any(), eq(null));
    }

    @Test
    void sessionCookieAndBearerBothAuthenticate() throws Exception {
        when(sessionService.resolveSession("sid-1")).thenReturn(new SessionInfo("alice", "Bearer at", false));
        mockMvc.perform(get("/api/v1/me").cookie(new Cookie(SessionCookieFilter.COOKIE_NAME, "sid-1")))
                .andExpect(status().isOk());

        when(oidcTokenVerifiers.authenticateBearer("Bearer good")).thenReturn("bob");
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer good"))
                .andExpect(status().isOk());

        when(oidcTokenVerifiers.authenticateBearer("Bearer bad"))
                .thenThrow(new NotebookApiException("UNAUTHENTICATED", "JWT validation failed", HttpStatus.UNAUTHORIZED));
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer bad"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutNeedsCsrfAndReturnsEndSessionUrl() throws Exception {
        when(sessionService.resolveSession("sid-1")).thenReturn(new SessionInfo("alice", "Bearer at", false));
        Cookie session = new Cookie(SessionCookieFilter.COOKIE_NAME, "sid-1");

        mockMvc.perform(post("/api/v1/auth/logout").cookie(session)).andExpect(status().isForbidden());

        when(sessionService.oidcIdToken("sid-1")).thenReturn(Optional.of("idt"));
        when(oidcLoginService.postLogoutRedirect(any())).thenReturn("http://nb/");
        when(oidcLoginService.endSessionUrl(Optional.of("idt"), "http://nb/")).thenReturn(Optional.of("https://kc.test/logout?id_token_hint=idt"));
        mockMvc.perform(post("/api/v1/auth/logout").cookie(session, new Cookie("XSRF-TOKEN", "t1")).header("X-XSRF-TOKEN", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endSessionUrl").value("https://kc.test/logout?id_token_hint=idt"));
        verify(sessionService).deleteSession("sid-1");
    }

    @Test
    void bearerPostIsNotSubjectToCookieCsrf() throws Exception {
        when(oidcTokenVerifiers.authenticateBearer("Bearer good")).thenReturn("bob");
        mockMvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer good"))
                .andExpect(status().isOk());
    }
}
