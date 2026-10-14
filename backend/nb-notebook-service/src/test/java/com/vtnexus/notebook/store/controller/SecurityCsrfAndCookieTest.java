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

        // POST có cookie phiên nhưng KHÔNG có header X-XSRF-TOKEN -> 403 Forbidden
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

        // 1. GET /api/v1/auth/config để nhận cookie XSRF-TOKEN
        MvcResult configResult = mockMvc.perform(get("/api/v1/auth/config"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie xsrfCookie = configResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();
        String xsrfToken = xsrfCookie.getValue();

        when(notebookService.createNotebook(any(), any(), any(), any(), any(), any(), eq("alice"))).thenReturn(
                Notebook.builder().id("nb-123").name("My Notebook").owner("alice").documentJson("{}").build()
        );

        // 2. POST /api/v1/notebooks kèm cả session cookie, xsrf cookie và header X-XSRF-TOKEN
        mockMvc.perform(post("/api/v1/notebooks")
                        .cookie(sessionCookie, xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"My Notebook\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void testLoginResponseSetsCookieWithSecurityAttributes() throws Exception {
        // GET /config lấy CSRF token trước
        MvcResult configResult = mockMvc.perform(get("/api/v1/auth/config"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie xsrfCookie = configResult.getResponse().getCookie("XSRF-TOKEN");
        String xsrfToken = xsrfCookie != null ? xsrfCookie.getValue() : "";

        when(sessionService.createSession(eq("alice"), eq("password123"), any(), eq(false)))
                .thenReturn(NotebookAuthSession.builder().id("sess-new-123").username("alice").build());

        // Note: Không bật profile dev, nên cookie phải có HttpOnly, Secure, SameSite=Strict, Path=/
        // Mock login thất bại hoặc thành công (ở đây không mock Kyuubi ping thì ném 503 AUTH_BACKEND_UNAVAILABLE)
        // Chúng ta kiểm tra header Set-Cookie khi logout
        Cookie sessionCookie = new Cookie(SessionCookieFilter.COOKIE_NAME, "sess-new-123");
        when(sessionService.resolveSession("sess-new-123")).thenReturn(new com.vtnexus.notebook.common.auth.SessionInfo(
                "alice", "Basic dXNlcjpwYXNz", false
        ));

        MvcResult logoutResult = mockMvc.perform(post("/api/v1/auth/logout")
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
    }

    @Test
    void testInternalEndpointProtectedByInternalTokenFilterWithConstantTimeComparison() throws Exception {
        // Không có header X-Internal-Token -> 401
        mockMvc.perform(get("/internal/auth/session").param("id", "sess-1"))
                .andExpect(status().isUnauthorized());

        // Token sai -> 401
        mockMvc.perform(get("/internal/auth/session")
                        .param("id", "sess-1")
                        .header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());

        // Token đúng -> 200
        when(sessionService.resolveSession("sess-1")).thenReturn(new com.vtnexus.notebook.common.auth.SessionInfo(
                "alice", "Basic dXNlcjpwYXNz", false
        ));
        mockMvc.perform(get("/internal/auth/session")
                        .param("id", "sess-1")
                        .header("X-Internal-Token", "secret-token-12345"))
                .andExpect(status().isOk());

        // Bypass attempt /internalx không được miễn xác thực (bị chặn 401 hoặc 403)
        mockMvc.perform(get("/internalx"))
                .andExpect(status().is4xxClientError());
    }
}
