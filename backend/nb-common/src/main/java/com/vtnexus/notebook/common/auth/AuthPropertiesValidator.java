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

        if (mode == AuthMode.NO_OIDC) {
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
            String redirectPath = env.getProperty("notebook.auth.oidc.redirect-path", "/auth/callback");
            if (!isValidRedirectPath(redirectPath)) {
                throw new IllegalStateException("notebook.auth.oidc.redirect-path must start with / and not be under /api/, /internal/ or /actuator/");
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

        String outputMode = env.getProperty("kyuubi.session-configs.kyuubi.engine.spark.output.mode");
        if (outputMode == null) {
            outputMode = env.getProperty("kyuubi.sessionConfigs.kyuubi.engine.spark.output.mode");
        }
        if (outputMode != null && !outputMode.isBlank()) {
            throw new IllegalStateException("output.mode must stay at Kyuubi default (Q7)");
        }
    }

    static boolean isValidRedirectPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("://")) {
            return false;
        }
        return !(path.startsWith("/api/") || path.startsWith("/internal/") || path.startsWith("/actuator/"));
    }
}
