package com.vtnexus.notebook.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.execution.client.DocumentServiceClient;
import com.vtnexus.notebook.execution.client.KyuubiRestClient;
import com.vtnexus.notebook.execution.dto.NotebookExecutionDto;
import com.vtnexus.notebook.execution.model.NotebookCellRun;
import com.vtnexus.notebook.execution.model.NotebookKyuubiSession;
import com.vtnexus.notebook.execution.model.NotebookRun;
import com.vtnexus.notebook.execution.repository.NotebookCellRunRepository;
import com.vtnexus.notebook.execution.repository.NotebookExecutionLogRepository;
import com.vtnexus.notebook.execution.repository.NotebookKyuubiSessionRepository;
import com.vtnexus.notebook.execution.repository.NotebookRunRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ProxyUserWireMockTest {

    private static WireMockServer wireMockServer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private NotebookKyuubiSessionRepository sessionRepository;
    private NotebookRunRepository runRepository;
    private NotebookCellRunRepository cellRunRepository;
    private NotebookExecutionLogRepository logRepository;
    private DocumentServiceClient documentServiceClient;
    private ServiceAccountTokenProvider serviceAccountTokenProvider;
    private KyuubiRestClient kyuubiRestClient;
    private ExecutionService executionService;
    private TransactionTemplate txTemplate;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();

        sessionRepository = Mockito.mock(NotebookKyuubiSessionRepository.class);
        runRepository = Mockito.mock(NotebookRunRepository.class);
        cellRunRepository = Mockito.mock(NotebookCellRunRepository.class);
        logRepository = Mockito.mock(NotebookExecutionLogRepository.class);
        documentServiceClient = Mockito.mock(DocumentServiceClient.class);

        when(documentServiceClient.canExecute(any(), any())).thenReturn(true);
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                return new SimpleTransactionStatus();
            }
            @Override
            public void commit(TransactionStatus status) throws TransactionException {}
            @Override
            public void rollback(TransactionStatus status) throws TransactionException {}
        };
        txTemplate = new TransactionTemplate(txManager);

        com.vtnexus.notebook.common.auth.AuthProperties saAuthProps = new com.vtnexus.notebook.common.auth.AuthProperties();
        saAuthProps.getOidc().setIssuer(wireMockServer.baseUrl() + "/auth");
        saAuthProps.getOidc().setServiceClientId("notebook-api");
        saAuthProps.getOidc().setServiceClientSecret("my-sa-secret");
        serviceAccountTokenProvider = new ServiceAccountTokenProvider(
                saAuthProps,
                new RestTemplateBuilder()
        );

        com.vtnexus.notebook.common.config.KyuubiProperties kyuubiProps = new com.vtnexus.notebook.common.config.KyuubiProperties();
        kyuubiProps.setRestUrl(wireMockServer.baseUrl());
        kyuubiProps.setPinToInstance(true);
        kyuubiProps.setAllowedSessionConfigKeys(List.of("spark.executor.memory"));
        kyuubiRestClient = new KyuubiRestClient(
                kyuubiProps,
                new RestTemplateBuilder(),
                objectMapper
        );

        com.vtnexus.notebook.common.auth.AuthProperties authProps = new com.vtnexus.notebook.common.auth.AuthProperties();
        authProps.setMode("oidc");
        com.vtnexus.notebook.common.config.NotebookProperties nbProps = new com.vtnexus.notebook.common.config.NotebookProperties();
        nbProps.getResults().setMaxRows(1000);

        executionService = new ExecutionService(
                sessionRepository,
                runRepository,
                cellRunRepository,
                logRepository,
                kyuubiRestClient,
                documentServiceClient,
                objectMapper,
                txTemplate,
                serviceAccountTokenProvider,
                authProps,
                nbProps
        );
    }

    @Test
    void testLdapModeAlwaysProxiesThroughLdapServiceAccount() {
        com.vtnexus.notebook.common.auth.AuthProperties ldapAuth = new com.vtnexus.notebook.common.auth.AuthProperties();
        ldapAuth.setMode("ldap");
        ldapAuth.getLdap().getServiceAccount().setUsername("kyuubi");
        ldapAuth.getLdap().getServiceAccount().setPassword("svc-pass");
        com.vtnexus.notebook.common.config.NotebookProperties nbProps = new com.vtnexus.notebook.common.config.NotebookProperties();
        ExecutionService ldapService = new ExecutionService(sessionRepository, runRepository, cellRunRepository, logRepository,
                kyuubiRestClient, documentServiceClient, objectMapper, txTemplate, serviceAccountTokenProvider, ldapAuth, nbProps);

        when(documentServiceClient.getRangerUsername("alice")).thenReturn("alice");
        when(sessionRepository.findFirstByNotebookIdAndUserIdAndStatusInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        String svcBasic = "Basic " + java.util.Base64.getEncoder().encodeToString("kyuubi:svc-pass".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"identifier\":\"kyuubi-session-ldap\",\"kyuubiInstance\":\"kyuubi-1:10099\"}")));

        ExecutionService.SessionCreationResult res = ldapService.getOrCreateSession("nb-1", "alice", svcBasic, new HashMap<>());

        assertThat(res.session().getProxied()).isTrue();
        assertThat(res.session().getEffectiveUser()).isEqualTo("alice");
        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/sessions"))
                .withHeader("Authorization", equalTo(svcBasic))
                .withRequestBody(containing("\"hive.server2.proxy.user\":\"alice\"")));
    }

    @Test
    void testAliceDirectUserOpenSessionUsesUserToken() {
        when(documentServiceClient.getRangerUsername("alice")).thenReturn("alice");
        when(sessionRepository.findFirstByNotebookIdAndUserIdAndStatusInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .withHeader("Authorization", equalTo("Bearer alice-user-token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"identifier\":\"kyuubi-session-alice\",\"kyuubiInstance\":\"kyuubi-1:10099\"}")));

        ExecutionService.SessionCreationResult res = executionService.getOrCreateSession(
                "nb-1", "alice", "Bearer alice-user-token", new HashMap<>());

        assertThat(res.session().getKyuubiSessionId()).isEqualTo("kyuubi-session-alice");
        assertThat(res.session().getProxied()).isFalse();
        assertThat(res.session().getEffectiveUser()).isEqualTo("alice");

        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/sessions"))
                .withHeader("Authorization", equalTo("Bearer alice-user-token"))
                .withRequestBody(notMatching(".*hive\\.server2\\.proxy\\.user.*")));
    }

    @Test
    void testUserCannotSetProxyUserThroughSessionConfigs() {
        when(documentServiceClient.getRangerUsername("alice")).thenReturn("alice");
        when(sessionRepository.findFirstByNotebookIdAndUserIdAndStatusInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"identifier\":\"kyuubi-session-alice\",\"kyuubiInstance\":\"kyuubi-1:10099\"}")));

        Map<String, String> configs = new HashMap<>();
        configs.put("hive.server2.proxy.user", "admin");
        configs.put("spark.executor.memory", "2g");
        ExecutionService.SessionCreationResult res = executionService.getOrCreateSession(
                "nb-1", "alice", "Bearer alice-user-token", configs);

        assertThat(res.warnings()).anyMatch(w -> w.contains("hive.server2.proxy.user"));
        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/sessions"))
                .withRequestBody(containing("\"spark.executor.memory\":\"2g\""))
                .withRequestBody(notMatching(".*hive\\.server2\\.proxy\\.user.*")));
    }

    @Test
    void testBobProxiedUserUsesServiceAccountTokenAndProxyUserConfig() {
        when(documentServiceClient.getRangerUsername("bob")).thenReturn("svc_bob");
        when(sessionRepository.findFirstByNotebookIdAndUserIdAndStatusInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        wireMockServer.stubFor(post(urlEqualTo("/auth/protocol/openid-connect/token"))
                .withRequestBody(containing("client_id=notebook-api"))
                .withRequestBody(containing("client_secret=my-sa-secret"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"sa-service-jwt-token\",\"expires_in\":300}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .withHeader("Authorization", equalTo("Bearer sa-service-jwt-token"))
                .withRequestBody(containing("\"hive.server2.proxy.user\":\"svc_bob\""))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"identifier\":\"kyuubi-session-bob-proxied\",\"kyuubiInstance\":\"kyuubi-1:10099\"}")));

        ExecutionService.SessionCreationResult res = executionService.getOrCreateSession(
                "nb-1", "bob", "Bearer bob-user-token", new HashMap<>());

        assertThat(res.session().getKyuubiSessionId()).isEqualTo("kyuubi-session-bob-proxied");
        assertThat(res.session().getProxied()).isTrue();
        assertThat(res.session().getEffectiveUser()).isEqualTo("svc_bob");

        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/sessions"))
                .withHeader("Authorization", equalTo("Bearer sa-service-jwt-token"))
                .withRequestBody(containing("\"hive.server2.proxy.user\":\"svc_bob\"")));
    }

    @Test
    void testKyuubiImpersonateErrorReturns403ProxyNotAllowed() {
        when(documentServiceClient.getRangerUsername("bob")).thenReturn("svc_bob");
        when(sessionRepository.findFirstByNotebookIdAndUserIdAndStatusInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        wireMockServer.stubFor(post(urlEqualTo("/auth/protocol/openid-connect/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"sa-service-jwt-token\",\"expires_in\":300}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/sessions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"User notebook-api is not allowed to impersonate svc_bob\"}")));

        assertThatThrownBy(() -> executionService.getOrCreateSession("nb-1", "bob", "Bearer bob-user-token", new HashMap<>()))
                .isInstanceOf(NotebookApiException.class)
                .satisfies(e -> {
                    NotebookApiException nae = (NotebookApiException) e;
                    assertThat(nae.getCode()).isEqualTo("PROXY_NOT_ALLOWED");
                    assertThat(nae.getStatus().value()).isEqualTo(403);
                });
    }

    @Test
    void testMissingSecretThrows503ProxyUnavailable() {
        com.vtnexus.notebook.common.auth.AuthProperties saAuthProps = new com.vtnexus.notebook.common.auth.AuthProperties();
        saAuthProps.getOidc().setIssuer(wireMockServer.baseUrl() + "/auth");
        saAuthProps.getOidc().setServiceClientId("notebook-api");
        saAuthProps.getOidc().setServiceClientSecret("");
        ServiceAccountTokenProvider noSecretProvider = new ServiceAccountTokenProvider(
                saAuthProps,
                new RestTemplateBuilder()
        );

        com.vtnexus.notebook.common.auth.AuthProperties authProps = new com.vtnexus.notebook.common.auth.AuthProperties();
        authProps.setMode("oidc");
        com.vtnexus.notebook.common.config.NotebookProperties nbProps = new com.vtnexus.notebook.common.config.NotebookProperties();
        nbProps.getResults().setMaxRows(1000);

        ExecutionService noSecretService = new ExecutionService(
                sessionRepository,
                runRepository,
                cellRunRepository,
                logRepository,
                kyuubiRestClient,
                documentServiceClient,
                objectMapper,
                new TransactionTemplate(new PlatformTransactionManager() {
                    public TransactionStatus getTransaction(TransactionDefinition d) { return new SimpleTransactionStatus(); }
                    public void commit(TransactionStatus s) {}
                    public void rollback(TransactionStatus s) {}
                }),
                noSecretProvider,
                authProps,
                nbProps
        );

        when(documentServiceClient.getRangerUsername("bob")).thenReturn("svc_bob");
        when(sessionRepository.findFirstByNotebookIdAndUserIdAndStatusInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> noSecretService.getOrCreateSession("nb-1", "bob", "Bearer bob-user-token", new HashMap<>()))
                .isInstanceOf(NotebookApiException.class)
                .satisfies(e -> {
                    NotebookApiException nae = (NotebookApiException) e;
                    assertThat(nae.getCode()).isEqualTo("PROXY_UNAVAILABLE");
                    assertThat(nae.getStatus().value()).isEqualTo(503);
                });
    }

    @Test
    void testExecutionLifecycleRunningStateThenFinishedState() throws Exception {
        String cellRunId = "crun-wm-1";
        String opId = "op-wm-1";

        NotebookRun run = NotebookRun.builder()
                .id("run-wm-1")
                .notebookId("nb-1")
                .triggeredBy("alice")
                .kyuubiSessionRef("inst-1|sess-1")
                .status("RUNNING")
                .build();

        NotebookCellRun cellRun = NotebookCellRun.builder()
                .id(cellRunId)
                .notebookRunId("run-wm-1")
                .executionId(opId)
                .language("SQL")
                .status("RUNNING")
                .build();

        when(cellRunRepository.findById(cellRunId)).thenReturn(Optional.of(cellRun));
        when(cellRunRepository.findByIdForUpdate(cellRunId)).thenReturn(Optional.of(cellRun));
        when(runRepository.findById("run-wm-1")).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate("run-wm-1")).thenReturn(Optional.of(run));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/operations/" + opId + "/event"))
                .inScenario("Lifecycle")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"state\":\"RUNNING_STATE\"}"))
                .willSetStateTo("QueryFinished"));

        wireMockServer.stubFor(get(urlMatching("/api/v1/operations/" + opId + "/log.*"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"logRowSet\":[\"Starting query...\"],\"rowCount\":1}")));

        NotebookExecutionDto poll1 = executionService.syncAndGetExecution(cellRunId, "alice", "Bearer alice-token");
        assertThat(poll1.getStatus()).isEqualTo("RUNNING");

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/operations/" + opId + "/event"))
                .inScenario("Lifecycle")
                .whenScenarioStateIs("QueryFinished")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"state\":\"FINISHED_STATE\"}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/operations/" + opId + "/resultsetmetadata"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"columns\":[{\"columnName\":\"val\",\"dataType\":\"STRING_TYPE\"}]}")));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/v1/operations/" + opId + "/rowset"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"rows\":[{\"fields\":[{\"dataType\":\"STRING_TYPE\",\"value\":\"result1\"}]}],\"rowCount\":1}")));

        wireMockServer.stubFor(put(urlEqualTo("/api/v1/operations/" + opId))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true}")));

        NotebookExecutionDto poll2 = executionService.syncAndGetExecution(cellRunId, "alice", "Bearer alice-token");
        assertThat(poll2.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(cellRun.getStatus()).isEqualTo("SUCCEEDED");

        wireMockServer.verify(getRequestedFor(urlEqualTo("/api/v1/operations/" + opId + "/resultsetmetadata")));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/api/v1/operations/" + opId + "/rowset")));
        wireMockServer.verify(putRequestedFor(urlEqualTo("/api/v1/operations/" + opId))
                .withRequestBody(containing("\"action\":\"close\"")));
    }
}
