package com.vtnexus.notebook.common.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenVerifierTest {

    static final String ISSUER = "https://10.29.19.56:32004/realms/lakehouse";
    static RSAKey key;
    static RSAKey otherKey;
    static JwtTokenVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("k2").generate();
        verifier = new JwtTokenVerifier(new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())),
                ISSUER, Set.of("kyuubi"), "preferred_username", List.of("RS256"), Duration.ofSeconds(30));
    }

    static JWTClaimsSet.Builder claims() {
        long now = System.currentTimeMillis();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(List.of("kyuubi", "account"))
                .subject("sub-1")
                .issueTime(new Date(now - 1000))
                .expirationTime(new Date(now + 300_000))
                .claim("preferred_username", "alice");
    }

    static String sign(RSAKey k, JWTClaimsSet c) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(k.getKeyID()).type(JOSEObjectType.JWT).build(), c);
        jwt.sign(new RSASSASigner(k));
        return jwt.serialize();
    }

    private void rejected(UnaryOperator<JWTClaimsSet.Builder> mutate) throws Exception {
        String token = sign(key, mutate.apply(claims()).build());
        assertThatThrownBy(() -> verifier.verifyAndGetUsername(token))
                .isInstanceOf(NotebookApiException.class)
                .extracting("code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void validTokenReturnsUsernameClaim() throws Exception {
        assertThat(verifier.verifyAndGetUsername(sign(key, claims().build()))).isEqualTo("alice");
    }

    @Test
    void rejectsWrongIssuerAudienceExpiryAndMissingClaims() throws Exception {
        rejected(b -> b.issuer("https://evil.example/realms/lakehouse"));
        rejected(b -> b.audience("account"));
        rejected(b -> b.expirationTime(new Date(System.currentTimeMillis() - 120_000)));
        rejected(b -> b.expirationTime(null));
        rejected(b -> b.issueTime(null));
        rejected(b -> b.issueTime(new Date(System.currentTimeMillis() + 600_000)));
        rejected(b -> b.claim("preferred_username", null));
    }

    @Test
    void rejectsUnknownKeyAndDisallowedAlgorithm() throws Exception {
        String foreign = sign(otherKey, claims().build());
        assertThatThrownBy(() -> verifier.verify(foreign)).isInstanceOf(NotebookApiException.class);

        SignedJWT hs = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims().build());
        hs.sign(new MACSigner(new byte[32]));
        String hsToken = hs.serialize();
        assertThatThrownBy(() -> verifier.verify(hsToken)).isInstanceOf(NotebookApiException.class);

        String unsigned = new com.nimbusds.jwt.PlainJWT(claims().build()).serialize();
        assertThatThrownBy(() -> verifier.verify(unsigned)).isInstanceOf(NotebookApiException.class);
        assertThatThrownBy(() -> verifier.verify("not-a-jwt")).isInstanceOf(NotebookApiException.class);
    }

    @Test
    void idTokenVerifierUsesClientIdAsAudience() throws Exception {
        JwtTokenVerifier idVerifier = new JwtTokenVerifier(new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())),
                ISSUER, Set.of("kyuubi-notebook"), "preferred_username", List.of("RS256"), Duration.ofSeconds(30));
        String idToken = sign(key, claims().audience("kyuubi-notebook").build());
        assertThat(idVerifier.verifyAndGetUsername(idToken)).isEqualTo("alice");
        assertThatThrownBy(() -> verifier.verify(idToken)).isInstanceOf(NotebookApiException.class);
    }
}
