package com.vtnexus.notebook.execution.config;

import com.vtnexus.notebook.common.auth.SessionCookieFilter;
import com.vtnexus.notebook.common.auth.SessionInfo;
import com.vtnexus.notebook.execution.client.InternalSessionClient;
import com.vtnexus.notebook.execution.dto.NotebookExecutionDto;
import com.vtnexus.notebook.execution.service.ExecutionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "notebook.auth.mode=no-oidc",
        "notebook.encryption-key=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=",
        "notebook.internal-token=secret-token-12345",
        "spring.datasource.url=jdbc:mariadb://localhost:3306/dummy",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDBDialect",
        "kyuubi.rest-url=http://127.0.0.1:1"
})
class ExecutionCsrfTest {

    private static final String XSRF = "csrf-token-abc";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InternalSessionClient internalSessionClient;

    @MockBean
    private ExecutionService executionService;

    private void stubSession() {
        when(internalSessionClient.resolve("sess-123"))
                .thenReturn(new SessionInfo("alice", "Basic YWxpY2U6cGFzcw==", false));
        when(executionService.submitExecution(anyString(), anyString(), anyString(), any()))
                .thenReturn(NotebookExecutionDto.builder().id("cr-1").status("RUNNING").build());
    }

    @Test
    void postWithSessionCookieButWithoutCsrfHeaderIsForbidden() throws Exception {
        stubSession();
        mockMvc.perform(post("/api/v1/notebook-sessions/s1/executions")
                        .cookie(new Cookie(SessionCookieFilter.COOKIE_NAME, "sess-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cellId\":\"c1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postWithMatchingCsrfCookieAndHeaderPasses() throws Exception {
        stubSession();
        mockMvc.perform(post("/api/v1/notebook-sessions/s1/executions")
                        .cookie(new Cookie(SessionCookieFilter.COOKIE_NAME, "sess-123"),
                                new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cellId\":\"c1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void controllerErrorIsNotTurnedIntoAuthenticationError() throws Exception {
        when(internalSessionClient.resolve("sess-123"))
                .thenReturn(new SessionInfo("alice", "Basic YWxpY2U6cGFzcw==", false));
        when(executionService.submitExecution(anyString(), anyString(), anyString(), any()))
                .thenThrow(new com.vtnexus.notebook.common.exception.NotebookApiException(
                        "NOTEBOOK_SESSION_NOT_FOUND", "Session not found", org.springframework.http.HttpStatus.NOT_FOUND));

        mockMvc.perform(post("/api/v1/notebook-sessions/s1/executions")
                        .cookie(new Cookie(SessionCookieFilter.COOKIE_NAME, "sess-123"),
                                new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", XSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cellId\":\"c1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void postWithMismatchedCsrfHeaderIsForbidden() throws Exception {
        stubSession();
        mockMvc.perform(post("/api/v1/notebook-sessions/s1/executions")
                        .cookie(new Cookie(SessionCookieFilter.COOKIE_NAME, "sess-123"),
                                new Cookie("XSRF-TOKEN", XSRF))
                        .header("X-XSRF-TOKEN", "other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cellId\":\"c1\"}"))
                .andExpect(status().isForbidden());
    }
}
