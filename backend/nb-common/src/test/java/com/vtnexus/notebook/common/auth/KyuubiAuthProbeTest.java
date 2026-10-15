package com.vtnexus.notebook.common.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class KyuubiAuthProbeTest {

    private WireMockServer wireMockServer;
    private AuthProperties authProperties;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        authProperties = new AuthProperties();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private com.vtnexus.notebook.common.config.KyuubiProperties kyuubiProps(String url) {
        com.vtnexus.notebook.common.config.KyuubiProperties p = new com.vtnexus.notebook.common.config.KyuubiProperties();
        p.setRestUrl(url);
        return p;
    }

    @Test
    void testProbeKyuubiNoneMode_whenAllowUnverifiedFalse_shouldThrowException() {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/ping"))
                .willReturn(aResponse().withStatus(200)));

        authProperties.setMode("no-oidc");
        authProperties.setAllowUnverified(false);

        KyuubiAuthProbe probe = new KyuubiAuthProbe(authProperties, kyuubiProps(wireMockServer.baseUrl()));

        IllegalStateException ex = assertThrows(IllegalStateException.class, probe::probeKyuubi);
        assertTrue(ex.getMessage().contains("Kyuubi accepts unauthenticated ping"));
    }

    @Test
    void testProbeKyuubiNoneMode_whenAllowUnverifiedTrue_shouldSetVerifiedFalseAndNotThrow() {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/ping"))
                .willReturn(aResponse().withStatus(200)));

        authProperties.setMode("no-oidc");
        authProperties.setAllowUnverified(true);

        KyuubiAuthProbe probe = new KyuubiAuthProbe(authProperties, kyuubiProps(wireMockServer.baseUrl()));
        assertDoesNotThrow(probe::probeKyuubi);

        assertFalse(authProperties.isEffectiveVerified());
    }

    @Test
    void testProbeKyuubiLdapMode_whenKyuubiRequiresBearer_shouldThrowException() {
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/ping"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", "Bearer realm=\"lakehouse\"")));

        authProperties.setMode("ldap");

        KyuubiAuthProbe probe = new KyuubiAuthProbe(authProperties, kyuubiProps(wireMockServer.baseUrl()));

        IllegalStateException ex = assertThrows(IllegalStateException.class, probe::probeKyuubi);
        assertTrue(ex.getMessage().contains("incompatible with notebook.auth.mode=ldap"));
    }

    @Test
    void testProbeKyuubiDown_shouldNotBlockStartup() {
        int unusedPort = wireMockServer.port();
        wireMockServer.stop();

        authProperties.setMode("oidc");

        KyuubiAuthProbe probe = new KyuubiAuthProbe(authProperties, kyuubiProps("http://localhost:" + unusedPort));
        assertDoesNotThrow(probe::probeKyuubi);
    }
}
