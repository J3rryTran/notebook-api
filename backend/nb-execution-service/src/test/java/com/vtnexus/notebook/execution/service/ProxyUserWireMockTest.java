package com.vtnexus.notebook.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.execution.client.DocumentServiceClient;
import com.vtnexus.notebook.execution.client.KyuubiRestClient;
import com.vtnexus.notebook.execution.model.NotebookKyuubiSession;
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
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

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
        kyuubiProps.setAllowedSessionConfigKeys(List.of("hive.server2.proxy.user", "spark.executor.memory"));
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
    void testAliceDirectUserOpenSessionUsesUserToken() {
        // ranger_username = alice (bằng username) -> không proxy
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

        // Không gửi hive.server2.proxy.user
        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/sessions"))
                .withHeader("Authorization", equalTo("Bearer alice-user-token"))
                .withRequestBody(notMatching(".*hive\\.server2\\.proxy\\.user.*")));
    }

    @Test
    void testBobProxiedUserUsesServiceAccountTokenAndProxyUserConfig() {
        // ranger_username = svc_bob (khác username bob) -> proxy user
        when(documentServiceClient.getRangerUsername("bob")).thenReturn("svc_bob");
        when(sessionRepository.findFirstByNotebookIdAndUserIdAndStatusInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        // Keycloak client_credentials token endpoint stub
        wireMockServer.stubFor(post(urlEqualTo("/auth/protocol/openid-connect/token"))
                .withRequestBody(containing("client_id=notebook-api"))
                .withRequestBody(containing("client_secret=my-sa-secret"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"sa-service-jwt-token\",\"expires_in\":300}")));

        // Kyuubi sessions endpoint stub
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
        saAuthProps.getOidc().setServiceClientSecret(""); // missing secret
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
}
