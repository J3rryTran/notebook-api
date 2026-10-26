package com.vtnexus.notebook.common.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.Base64;

public class AuthPropertiesValidator implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        validate(environment);
    }

    public static void validate(Environment env) {
        String modeStr = env.getProperty("notebook.auth.mode");
        if (modeStr == null || modeStr.isBlank()) {
            throw new IllegalStateException("notebook.auth.mode must be one of oidc, no-oidc, ldap");
        }
        AuthMode mode = AuthMode.fromString(modeStr);
        if (mode == null) {
            throw new IllegalStateException("notebook.auth.mode must be one of oidc, no-oidc, ldap");
        }

        String internalToken = null;
        try {
            internalToken = env.getProperty("notebook.internalToken");
            if (internalToken == null || internalToken.isBlank()) {
                internalToken = env.getProperty("notebook.internal-token");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("NOTEBOOK_INTERNAL_TOKEN is required", e);
        }
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("NOTEBOOK_INTERNAL_TOKEN is required");
        }

        if (mode == AuthMode.NO_OIDC || mode == AuthMode.OIDC) {
            String encKey = env.getProperty("notebook.encryption-key");
            if (encKey == null || encKey.isBlank()) {
                encKey = env.getProperty("notebook.encryptionKey");
            }
            if (encKey == null || encKey.isBlank()) {
                throw new IllegalStateException("notebook.encryptionKey must be 32 bytes base64");
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(encKey.trim());
                if (decoded.length != 32) {
                    throw new IllegalStateException("notebook.encryptionKey must be 32 bytes base64");
                }
            } catch (Exception e) {
                throw new IllegalStateException("notebook.encryptionKey must be 32 bytes base64");
            }
        }

        boolean isDev = env.acceptsProfiles(Profiles.of("dev"));

        if (mode == AuthMode.OIDC) {
            String p = "notebook.auth.oidc.";
            requireNonBlank(env, p + "jwt.issuer");
            requireNonBlank(env, p + "jwt.jwks-url");
            requireNonBlank(env, p + "ui.client-id");
            requireNonBlank(env, p + "ui.client-secret");
            requireNonBlank(env, p + "authorization-endpoint");
            requireNonBlank(env, p + "token-endpoint");
            if (env.getProperty(p + "jwt.audience[0]", env.getProperty(p + "jwt.audience", "")).isBlank()) {
                throw new IllegalStateException(p + "jwt.audience is required for mode=oidc");
            }
            for (String key : new String[]{"jwt.jwks-url", "authorization-endpoint", "token-endpoint", "end-session-endpoint"}) {
                String v = env.getProperty(p + key, "");
                if (!v.isBlank() && !isHttpUrl(v)) {
                    throw new IllegalStateException(p + key + " must be an http(s) URL");
                }
            }
            String redirectUri = env.getProperty(p + "redirect-uri", "");
            if (!redirectUri.isBlank() && !isValidRedirectUri(redirectUri)) {
                throw new IllegalStateException(p + "redirect-uri must be an http(s) URL ending with " + OIDC_CALLBACK_PATH);
            }
        }

        if (mode == AuthMode.LDAP) {
            String saUser = env.getProperty("notebook.auth.ldap.service-account.username");
            String saPass = env.getProperty("notebook.auth.ldap.service-account.password");
            if (saUser == null || saUser.isBlank() || saPass == null || saPass.isEmpty()) {
                throw new IllegalStateException("notebook.auth.ldap.service-account.username and password are required for mode=ldap");
            }
            String url = env.getProperty("notebook.auth.ldap.url");
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("notebook.auth.ldap.url is required");
            }

            String bindDn = env.getProperty("notebook.auth.ldap.bind-dn", "");
            String bindPassword = env.getProperty("notebook.auth.ldap.bind-password", "");
            if (bindDn.isBlank() != bindPassword.isEmpty()) {
                throw new IllegalStateException("notebook.auth.ldap.bind-dn and bind-password must be set together");
            }

            String startTlsStr = env.getProperty("notebook.auth.ldap.start-tls");
            if (startTlsStr == null) startTlsStr = env.getProperty("notebook.auth.ldap.startTls", "false");
            boolean startTls = Boolean.parseBoolean(startTlsStr);
            if (url.trim().toLowerCase().startsWith("ldap://") && !startTls && !isDev) {
                throw new IllegalStateException("plain ldap:// requires startTls=true outside dev");
            }

            String trustAllStr = env.getProperty("notebook.auth.ldap.trust-all");
            if (trustAllStr == null) trustAllStr = env.getProperty("notebook.auth.ldap.trustAll", "false");
            boolean trustAll = Boolean.parseBoolean(trustAllStr);
            if (trustAll && !isDev) {
                throw new IllegalStateException("trustAll is only allowed in dev");
            }
        }

        String outputMode = env.getProperty("kyuubi.session-configs.kyuubi.engine.spark.output.mode");
        if (outputMode == null) {
            outputMode = env.getProperty("kyuubi.sessionConfigs.kyuubi.engine.spark.output.mode");
        }
        if (outputMode != null && !outputMode.isBlank()) {
            throw new IllegalStateException("output.mode must stay at Kyuubi default (Q7)");
        }
    }

    public static final String OIDC_CALLBACK_PATH = "/api/v1/auth/callback";

    private static void requireNonBlank(Environment env, String key) {
        String v = env.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(key + " is required for mode=oidc");
        }
    }

    static boolean isHttpUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean isValidRedirectUri(String value) {
        if (!isHttpUrl(value)) {
            return false;
        }
        java.net.URI uri = java.net.URI.create(value.trim());
        return OIDC_CALLBACK_PATH.equals(uri.getPath()) && uri.getQuery() == null && uri.getFragment() == null;
    }
}
