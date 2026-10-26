package com.vtnexus.notebook.common.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.springframework.http.HttpStatus;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class JwtTokenVerifier {

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final String usernameClaim;
    private final Duration clockSkew;

    public JwtTokenVerifier(AuthProperties.Jwt jwt, Set<String> audiences) {
        this(remoteSource(jwt), jwt.getIssuer(), audiences, jwt.getUsernameClaim(), jwt.getAllowedAlgorithms(), jwt.getClockSkew());
    }

    public JwtTokenVerifier(
            JWKSource<SecurityContext> keySource,
            String issuer,
            Set<String> audiences,
            String usernameClaim,
            List<String> allowedAlgorithms,
            Duration clockSkew) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer is required");
        }
        if (audiences == null || audiences.isEmpty()) {
            throw new IllegalArgumentException("JWT audience is required");
        }
        this.usernameClaim = (usernameClaim == null || usernameClaim.isBlank()) ? "preferred_username" : usernameClaim.trim();
        this.clockSkew = clockSkew != null ? clockSkew : Duration.ofSeconds(30);

        List<String> algNames = (allowedAlgorithms == null || allowedAlgorithms.isEmpty()) ? List.of("RS256") : allowedAlgorithms;
        Set<JWSAlgorithm> algorithms = algNames.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(JWSAlgorithm::parse)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, keySource));
        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
                new LinkedHashSet<>(audiences),
                new JWTClaimsSet.Builder().issuer(issuer.trim()).build(),
                Set.of("exp", "iat"),
                Set.of());
        claimsVerifier.setMaxClockSkew((int) this.clockSkew.toSeconds());
        p.setJWTClaimsSetVerifier(claimsVerifier);
        this.processor = p;
    }

    private static JWKSource<SecurityContext> remoteSource(AuthProperties.Jwt jwt) {
        if (jwt.getJwksUrl() == null || jwt.getJwksUrl().isBlank()) {
            throw new IllegalArgumentException("JWT jwks-url is required");
        }
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(
                (int) jwt.getConnectTimeout().toMillis(), (int) jwt.getReadTimeout().toMillis());
        try {
            return JWKSourceBuilder.<SecurityContext>create(new URL(jwt.getJwksUrl().trim()), retriever).build();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid JWT jwks-url: " + jwt.getJwksUrl(), e);
        }
    }

    public JWTClaimsSet verify(String token) {
        if (token == null || token.isBlank()) {
            throw unauthenticated("Token is empty");
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(token, null);
        } catch (ParseException e) {
            throw unauthenticated("Malformed JWT");
        } catch (KeySourceException e) {
            throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "Cannot load signing keys from the identity provider", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (Exception e) {
            throw unauthenticated("JWT validation failed: " + e.getMessage());
        }
        Date iat = claims.getIssueTime();
        if (iat != null && iat.getTime() > System.currentTimeMillis() + clockSkew.toMillis()) {
            throw unauthenticated("JWT 'iat' is in the future");
        }
        return claims;
    }

    public String username(JWTClaimsSet claims) {
        String username;
        try {
            username = claims.getStringClaim(usernameClaim);
        } catch (ParseException e) {
            username = null;
        }
        if (username == null || username.isBlank()) {
            throw unauthenticated("JWT is missing the username claim '" + usernameClaim + "'");
        }
        return username;
    }

    public String verifyAndGetUsername(String token) {
        return username(verify(token));
    }

    private static NotebookApiException unauthenticated(String message) {
        return new NotebookApiException("UNAUTHENTICATED", message, HttpStatus.UNAUTHORIZED);
    }
}
