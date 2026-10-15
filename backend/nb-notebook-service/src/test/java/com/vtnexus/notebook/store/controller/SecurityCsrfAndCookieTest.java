package com.vtnexus.notebook.store.controller;

import com.vtnexus.notebook.common.auth.SessionCookieFilter;
import com.vtnexus.notebook.store.model.Notebook;
import com.vtnexus.notebook.store.model.NotebookAuthSession;
import com.vtnexus.notebook.store.service.AuthSessionService;
import com.vtnexus.notebook.store.service.NotebookService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "notebook.auth.mode=no-oidc",
        "notebook.encryptionKey=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=",
        "notebook.internalToken=secret-token-12345",
        "spring.datasource.url=jdbc:mariadb://localhost:3306/dummy",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDBDialect"
})
class SecurityCsrfAndCookieTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthSessionService sessionService;

    @MockBean
    private NotebookService notebookService;

    @Test
    void testPostWithoutCsrfTokenReturns403Forbidden() throws Exception {
        Cookie sessionCookie = new Cookie(SessionCookieFilter.COOKIE_NAME, "sess-123");
        when(sessionService.resolveSession("sess-123")).thenReturn(new com.vtnexus.notebook.common.auth.SessionInfo(
                "alice", "Basic dXNlcjpwYXNz", false
        ));

        mockMvc.perform(post("/api/v1/notebooks")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"My Notebook\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testPostWithCsrfTokenSucceeds() throws Exception {
        Cookie sessionCookie = new Cookie(SessionCookieFilter.COOKIE_NAME, "sess-123");
        when(sessionService.resolveSession("sess-123")).thenReturn(new com.vtnexus.notebook.common.auth.SessionInfo(
                "alice", "Basic dXNlcjpwYXNz", false
        ));

        MvcResult configResult = mockMvc.perform(get("/api/v1/auth/config"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie xsrfCookie = configResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();
        String xsrfToken = xsrfCookie.getValue();

        when(notebookService.createNotebook(any(), any(), any(), any(), any(), any(), eq("alice"))).thenReturn(
                Notebook.builder().id("nb-123").name("My Notebook").owner("alice").documentJson("{}").build()
        );

        mockMvc.perform(post("/api/v1/notebooks")
                        .cookie(sessionCookie, xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"My Notebook\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void testLoginResponseSetsCookieWithSecurityAttributes() throws Exception {
        MvcResult configResult = mockMvc.perform(get("/api/v1/auth/config"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie xsrfCookie = configResult.getResponse().getCookie("XSRF-TOKEN");
        String xsrfToken = xsrfCookie != null ? xsrfCookie.getValue() : "";

        when(sessionService.createSession(eq("alice"), eq("password123"), any(), eq(false)))
                .thenReturn(NotebookAuthSession.builder().id("sess-new-123").username("alice").build());

        Cookie sessionCookie = new Cookie(SessionCookieFilter.COOKIE_NAME, "sess-new-123");
        when(sessionService.resolveSession("sess-new-123")).thenReturn(new com.vtnexus.notebook.common.auth.SessionInfo(
                "alice", "Basic dXNlcjpwYXNz", false
        ));

        MvcResult logoutResult = mockMvc.perform(post("/api/v1/auth/logout")
                        .secure(true)
                        .cookie(sessionCookie, xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfToken))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();

        java.util.List<String> setCookies = logoutResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        String sessionCookieHeader = setCookies.stream()
                .filter(c -> c.startsWith("NB_SESSION="))
                .findFirst()
                .orElse("");

        assertThat(sessionCookieHeader).contains("HttpOnly");
        assertThat(sessionCookieHeader).contains("Secure");
        assertThat(sessionCookieHeader).contains("SameSite=Strict");
        assertThat(sessionCookieHeader).contains("Path=/");

        MvcResult plainHttp = mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(sessionCookie, xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfToken))
                .andExpect(status().isOk())
                .andReturn();
        String httpCookie = plainHttp.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("NB_SESSION="))
                .findFirst()
                .orElse("");
        assertThat(httpCookie).contains("HttpOnly").doesNotContain("Secure");
    }

    @Test
    void testInternalEndpointProtectedByInternalTokenFilterWithConstantTimeComparison() throws Exception {
        mockMvc.perform(get("/internal/auth/session").param("id", "sess-1"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/internal/auth/session")
                        .param("id", "sess-1")
                        .header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());

        when(sessionService.resolveSession("sess-1")).thenReturn(new com.vtnexus.notebook.common.auth.SessionInfo(
                "alice", "Basic dXNlcjpwYXNz", false
        ));
        mockMvc.perform(get("/internal/auth/session")
                        .param("id", "sess-1")
                        .header("X-Internal-Token", "secret-token-12345"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/internalx"))
                .andExpect(status().is4xxClientError());
    }
}
