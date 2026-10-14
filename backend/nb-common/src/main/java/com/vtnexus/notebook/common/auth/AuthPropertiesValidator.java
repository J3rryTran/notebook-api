package com.vtnexus.notebook.common.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Base64;

public class AuthPropertiesValidator implements EnvironmentPostProcessor {

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

        // 2. Encryption key cho no-oidc và ldap
        if (mode == AuthMode.NO_OIDC || mode == AuthMode.LDAP) {
            String encKey = env.getProperty("notebook.encryptionKey");
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

        // Profile kiểm tra
        String[] activeProfiles = env.getActiveProfiles();
        boolean isDev = Arrays.asList(activeProfiles).contains("dev");

        // 3-7. Cấu hình mode LDAP
        if (mode == AuthMode.LDAP) {
            String url = env.getProperty("notebook.auth.ldap.url");
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("notebook.auth.ldap.url is required");
            }

            String userDnPattern = env.getProperty("notebook.auth.ldap.userDnPattern");
            String userSearchFilter = env.getProperty("notebook.auth.ldap.userSearchFilter");
            boolean hasPattern = userDnPattern != null && !userDnPattern.isBlank();
            boolean hasFilter = userSearchFilter != null && !userSearchFilter.isBlank();

            if ((hasPattern && hasFilter) || (!hasPattern && !hasFilter)) {
                throw new IllegalStateException("set exactly one of userDnPattern or userSearchFilter");
            }

            if (hasFilter) {
                String managerDn = env.getProperty("notebook.auth.ldap.managerDn");
                if (managerDn == null || managerDn.isBlank()) {
                    throw new IllegalStateException("managerDn is required when using userSearch");
                }
            }

            boolean startTls = Boolean.parseBoolean(env.getProperty("notebook.auth.ldap.startTls", "false"));
            if (url.trim().toLowerCase().startsWith("ldap://") && !startTls && !isDev) {
                throw new IllegalStateException("plain ldap:// requires startTls=true outside dev");
            }

            boolean trustAll = Boolean.parseBoolean(env.getProperty("notebook.auth.ldap.trustAll", "false"));
            if (trustAll && !isDev) {
                throw new IllegalStateException("trustAll is only allowed in dev");
            }
        }

        // 8. internalToken bắt buộc
        String internalToken = env.getProperty("notebook.internalToken");
        if (internalToken == null || internalToken.isBlank()) {
            internalToken = env.getProperty("notebook.internal-token");
        }
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("NOTEBOOK_INTERNAL_TOKEN is required");
        }

        // 9. Không được đặt kyuubi.engine.spark.output.mode
        String outputMode = env.getProperty("kyuubi.session-configs.kyuubi.engine.spark.output.mode");
        if (outputMode == null) {
            outputMode = env.getProperty("kyuubi.sessionConfigs.kyuubi.engine.spark.output.mode");
        }
        if (outputMode != null && !outputMode.isBlank()) {
            throw new IllegalStateException("output.mode must stay at Kyuubi default (Q7)");
        }
    }
}
