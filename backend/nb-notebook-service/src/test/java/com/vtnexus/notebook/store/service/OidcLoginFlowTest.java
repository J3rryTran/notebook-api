package com.vtnexus.notebook.store.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.vtnexus.notebook.common.auth.AuthProperties;
import com.vtnexus.notebook.common.auth.JwtTokenVerifier;
import com.vtnexus.notebook.common.auth.OidcTokenVerifiers;
import com.vtnexus.notebook.common.auth.SessionInfo;
import com.vtnexus.notebook.common.config.KyuubiProperties;
import com.vtnexus.notebook.common.config.NotebookProperties;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.model.NotebookAuthSession;
import com.vtnexus.notebook.store.model.NotebookOidcPendingLogin;
import com.vtnexus.notebook.store.repository.NotebookAuthSessionRepository;
import com.vtnexus.notebook.store.repository.NotebookOidcPendingLoginRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OidcLoginFlowTest {

    static final String ISSUER = "https://10.29.19.56:32004/realms/lakehouse";
    static final String CLIENT_ID = "kyuubi-notebook";
    static final String REDIRECT_URI = "http://10.29.19.56:32380/api/v1/auth/callback";
    static RSAKey key;

    WireMockServer keycloak;
    AuthProperties authProperties;
    Map<String, NotebookAuthSession> sessions;
    Map<String, NotebookOidcPendingLogin> pending;
    AuthSessionService sessionService;
    OidcLoginService loginService;

    @BeforeAll
    static void keys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
    }

    @BeforeEach
    void setUp() {
        keycloak = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        keycloak.start();

        authProperties = new AuthProperties();
        authProperties.setMode("oidc");
        AuthProperties.OidcProperties oidc = authProperties.getOidc();
        oidc.getJwt().setIssuer(ISSUER);
        oidc.getJwt().setAudience(List.of("kyuubi"));
        oidc.getJwt().setJwksUrl(keycloak.baseUrl() + "/certs");
        oidc.getUi().setClientId(CLIENT_ID);
        oidc.getUi().setClientSecret("ui-secret");
        oidc.setAuthorizationEndpoint(ISSUER + "/protocol/openid-connect/auth");
        oidc.setTokenEndpoint(keycloak.baseUrl() + "/token");
        oidc.setEndSessionEndpoint(ISSUER + "/protocol/openid-connect/logout");
        oidc.setRedirectUri(REDIRECT_URI);

        OidcTokenVerifiers verifiers = new OidcTokenVerifiers(authProperties);
        ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()));
        verifiers.setForTesting(
                new JwtTokenVerifier(jwks, ISSUER, Set.of("kyuubi"), "preferred_username", List.of("RS256"), Duration.ofSeconds(30)),
                new JwtTokenVerifier(jwks, ISSUER, Set.of(CLIENT_ID), "preferred_username", List.of("RS256"), Duration.ofSeconds(30)));

        sessions = new ConcurrentHashMap<>();
        NotebookAuthSessionRepository sessionRepo = mock(NotebookAuthSessionRepository.class);
        when(sessionRepo.save(any())).thenAnswer(i -> {
            NotebookAuthSession s = i.getArgument(0);
            sessions.put(s.getId(), s);
            return s;
        });
        when(sessionRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(sessions.get(i.<String>getArgument(0))));
        when(sessionRepo.findByIdForUpdate(anyString())).thenAnswer(i -> Optional.ofNullable(sessions.get(i.<String>getArgument(0))));
        doAnswer(i -> sessions.remove(i.<NotebookAuthSession>getArgument(0).getId())).when(sessionRepo).delete(any());
        doAnswer(i -> sessions.remove(i.<String>getArgument(0))).when(sessionRepo).deleteById(anyString());

        pending = new ConcurrentHashMap<>();
        NotebookOidcPendingLoginRepository pendingRepo = mock(NotebookOidcPendingLoginRepository.class);
        when(pendingRepo.save(any())).thenAnswer(i -> {
            NotebookOidcPendingLogin p = i.getArgument(0);
            pending.put(p.getState(), p);
            return p;
        });
        when(pendingRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(pending.get(i.<String>getArgument(0))));
        when(pendingRepo.deleteByState(anyString())).thenAnswer(i -> pending.remove(i.<String>getArgument(0)) != null ? 1 : 0);

        NotebookProperties notebookProperties = new NotebookProperties();
        notebookProperties.setEncryptionKey("MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=");
        notebookProperties.setAdmins(List.of("alice"));
        KyuubiProperties kyuubiProperties = new KyuubiProperties();
        kyuubiProperties.setRestUrl("http://kyuubi.invalid:10099");

        ObjectMapper mapper = new ObjectMapper();
        OidcTokenClient tokenClient = new OidcTokenClient(authProperties, mapper);
        sessionService = new AuthSessionService(sessionRepo, authProperties, mock(LdapAuthenticator.class),
                notebookProperties, kyuubiProperties, tokenClient, verifiers, mapper);
        loginService = new OidcLoginService(authProperties, pendingRepo, tokenClient, verifiers, sessionService);
    }

    @AfterEach
    void tearDown() {
        keycloak.stop();
    }

    static String jwt(String audience, String user, String nonce, long ttlSeconds) throws Exception {
        long now = System.currentTimeMillis();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(audience).subject("sub-" + user)
                .issueTime(new Date(now - 1000)).expirationTime(new Date(now + ttlSeconds * 1000))
                .claim("preferred_username", user);
        if (nonce != null) {
            b.claim("nonce", nonce);
        }
        SignedJWT s = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), b.build());
        s.sign(new RSASSASigner(key));
        return s.serialize();
    }

    static Map<String, String> query(String url) {
        Map<String, String> out = new HashMap<>();
        for (String kv : URI.create(url).getRawQuery().split("&")) {
            String[] p = kv.split("=", 2);
            out.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return out;
    }

    void stubToken(String access, String refresh, String id, int expiresIn) {
        keycloak.stubFor(post("/token").willReturn(okJson(String.format(
                "{\"access_token\":\"%s\",\"refresh_token\":\"%s\",\"id_token\":\"%s\",\"expires_in\":%d,\"token_type\":\"Bearer\"}",
                access, refresh, id, expiresIn))));
    }

    String startLogin(String redirect) {
        return loginService.buildAuthorizationRedirect(new MockHttpServletRequest(), redirect);
    }

    @Test
    void authorizationRedirectCarriesPkceStateNonceAndConfiguredRedirectUri() {
        Map<String, String> q = query(startLogin("/?nb=1"));
        assertThat(q).containsEntry("response_type", "code")
                .containsEntry("client_id", CLIENT_ID)
                .containsEntry("redirect_uri", REDIRECT_URI)
                .containsEntry("scope", "openid profile email")
                .containsEntry("code_challenge_method", "S256");
        NotebookOidcPendingLogin p = pending.get(q.get("state"));
        assertThat(p).isNotNull();
        assertThat(p.getNonce()).isEqualTo(q.get("nonce"));
        assertThat(p.getRedirectPath()).isEqualTo("/?nb=1");
        assertThat(OidcLoginService.codeChallengeS256(p.getCodeVerifier())).isEqualTo(q.get("code_challenge"));
        assertThat(p.getCodeVerifier().length()).isBetween(43, 128);
    }

    @Test
    void callbackExchangesCodeCreatesEncryptedSessionAndResolvesBearer() throws Exception {
        Map<String, String> q = query(startLogin("/notebooks"));
        String access = jwt("kyuubi", "alice", null, 300);
        stubToken(access, "refresh-1", jwt(CLIENT_ID, "alice", q.get("nonce"), 300), 300);
        String verifier = pending.get(q.get("state")).getCodeVerifier();

        OidcLoginService.LoginResult result = loginService.completeLogin("code-1", q.get("state"));

        assertThat(result.redirectPath()).isEqualTo("/notebooks");
        keycloak.verify(postRequestedFor(urlEqualTo("/token"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=code-1"))
                .withRequestBody(containing("client_secret=ui-secret"))
                .withRequestBody(containing("code_verifier=" + verifier))
                .withRequestBody(containing("redirect_uri=" + java.net.URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8))));

        NotebookAuthSession stored = sessions.get(result.session().getId());
        assertThat(stored.getAuthMode()).isEqualTo("oidc");
        assertThat(new String(stored.getCredentialEnc(), StandardCharsets.ISO_8859_1)).doesNotContain(access.substring(0, 20));
        assertThat(stored.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofHours(3)));

        SessionInfo info = sessionService.resolveSession(result.session().getId());
        assertThat(info.username()).isEqualTo("alice");
        assertThat(info.authorizationHeader()).isEqualTo("Bearer " + access);
        assertThat(info.isAdmin()).isTrue();
        assertThat(pending).isEmpty();
    }

    @Test
    void stateCanBeUsedOnlyOnceEvenWhenExchangeFails() {
        Map<String, String> q = query(startLogin("/"));
        keycloak.stubFor(post("/token").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_grant\"}")));

        assertThatThrownBy(() -> loginService.completeLogin("bad", q.get("state")))
                .isInstanceOf(NotebookApiException.class).hasMessageContaining("invalid_grant");
        assertThatThrownBy(() -> loginService.completeLogin("bad", q.get("state")))
                .isInstanceOf(NotebookApiException.class).hasMessageContaining("Unknown or already used");
        assertThatThrownBy(() -> loginService.completeLogin("x", "never-issued"))
                .isInstanceOf(NotebookApiException.class);
    }

    @Test
    void rejectsNonceMismatchAndAccessTokenWithoutKyuubiAudience() throws Exception {
        Map<String, String> q1 = query(startLogin("/"));
        stubToken(jwt("kyuubi", "alice", null, 300), "r", jwt(CLIENT_ID, "alice", "other-nonce", 300), 300);
        assertThatThrownBy(() -> loginService.completeLogin("c", q1.get("state")))
                .isInstanceOf(NotebookApiException.class).hasMessageContaining("nonce");

        Map<String, String> q2 = query(startLogin("/"));
        stubToken(jwt("account", "alice", null, 300), "r", jwt(CLIENT_ID, "alice", q2.get("nonce"), 300), 300);
        assertThatThrownBy(() -> loginService.completeLogin("c", q2.get("state")))
                .isInstanceOf(NotebookApiException.class).extracting("code").isEqualTo("UNAUTHENTICATED");
        assertThat(sessions).isEmpty();
    }

    @Test
    void expiringAccessTokenIsRefreshedAndRejectedRefreshDropsSession() throws Exception {
        Map<String, String> q = query(startLogin("/"));
        stubToken(jwt("kyuubi", "alice", null, 300), "refresh-1", jwt(CLIENT_ID, "alice", q.get("nonce"), 300), 30);
        String sid = loginService.completeLogin("c", q.get("state")).session().getId();

        String renewed = jwt("kyuubi", "alice", null, 300);
        keycloak.resetAll();
        stubToken(renewed, "refresh-2", jwt(CLIENT_ID, "alice", null, 300), 300);
        assertThat(sessionService.resolveSession(sid).authorizationHeader()).isEqualTo("Bearer " + renewed);
        keycloak.verify(postRequestedFor(urlEqualTo("/token"))
                .withRequestBody(containing("grant_type=refresh_token"))
                .withRequestBody(containing("refresh_token=refresh-1")));

        keycloak.resetAll();
        assertThat(sessionService.resolveSession(sid).authorizationHeader()).isEqualTo("Bearer " + renewed);
        keycloak.verify(0, postRequestedFor(urlEqualTo("/token")));

        Map<String, String> q2 = query(startLogin("/"));
        stubToken(jwt("kyuubi", "alice", null, 300), "refresh-x", jwt(CLIENT_ID, "alice", q2.get("nonce"), 300), 10);
        String sid2 = loginService.completeLogin("c", q2.get("state")).session().getId();
        keycloak.resetAll();
        keycloak.stubFor(post("/token").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_grant\"}")));
        assertThat(sessionService.resolveSession(sid2)).isNull();
        assertThat(sessions).doesNotContainKey(sid2);
    }

    @Test
    void logoutUrlUsesIdTokenHintAndPostLogoutRedirect() throws Exception {
        Map<String, String> q = query(startLogin("/"));
        String idToken = jwt(CLIENT_ID, "alice", q.get("nonce"), 300);
        stubToken(jwt("kyuubi", "alice", null, 300), "r", idToken, 300);
        String sid = loginService.completeLogin("c", q.get("state")).session().getId();

        String url = loginService.endSessionUrl(sessionService.oidcIdToken(sid),
                loginService.postLogoutRedirect(new MockHttpServletRequest())).orElseThrow();
        Map<String, String> lq = query(url);
        assertThat(url).startsWith(ISSUER + "/protocol/openid-connect/logout?");
        assertThat(lq).containsEntry("id_token_hint", idToken)
                .containsEntry("post_logout_redirect_uri", "http://10.29.19.56:32380/")
                .containsEntry("client_id", CLIENT_ID);
    }

    @Test
    void redirectPathIsRestrictedToLocalNonApiPaths() {
        assertThat(OidcLoginService.sanitizeRedirectPath("/notebooks/1")).isEqualTo("/notebooks/1");
        assertThat(OidcLoginService.sanitizeRedirectPath(null)).isEqualTo("/");
        assertThat(OidcLoginService.sanitizeRedirectPath("https://evil.example/")).isEqualTo("/");
        assertThat(OidcLoginService.sanitizeRedirectPath("//evil.example/")).isEqualTo("/");
        assertThat(OidcLoginService.sanitizeRedirectPath("/\\evil.example")).isEqualTo("/");
        assertThat(OidcLoginService.sanitizeRedirectPath("/api/v1/auth/logout")).isEqualTo("/");
        assertThat(OidcLoginService.withOpenIdScope("profile email")).isEqualTo("openid profile email");
    }
}
