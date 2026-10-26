package com.vtnexus.notebook.common.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AuthPropertiesValidatorTest {

    private String validKey() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    private MockEnvironment validOidcEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("notebook.auth.mode", "oidc");
        env.setProperty("notebook.internalToken", "secret-token-123");
        env.setProperty("notebook.encryptionKey", validKey());
        env.setProperty("notebook.auth.oidc.jwt.issuer", "https://10.29.19.56:32004/realms/lakehouse");
        env.setProperty("notebook.auth.oidc.jwt.audience[0]", "kyuubi");
        env.setProperty("notebook.auth.oidc.jwt.jwks-url", "http://keycloak:8080/realms/lakehouse/protocol/openid-connect/certs");
        env.setProperty("notebook.auth.oidc.ui.client-id", "kyuubi-notebook");
        env.setProperty("notebook.auth.oidc.ui.client-secret", "s3cret");
        env.setProperty("notebook.auth.oidc.authorization-endpoint", "https://10.29.19.56:32004/realms/lakehouse/protocol/openid-connect/auth");
        env.setProperty("notebook.auth.oidc.token-endpoint", "http://keycloak:8080/realms/lakehouse/protocol/openid-connect/token");
        env.setProperty("notebook.auth.oidc.redirect-uri", "http://10.29.19.56:32380/api/v1/auth/callback");
        return env;
    }

    @Test
    @DisplayName("T5.1: mode không hợp lệ")
    void testInvalidMode() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("notebook.auth.mode", "unknown-mode");
        env.setProperty("notebook.internalToken", "secret");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
        assertEquals("notebook.auth.mode must be one of oidc, no-oidc, ldap", ex.getMessage());
    }

    @Test
    @DisplayName("T5.2: mode=no-oidc thiếu hoặc sai encryptionKey")
    void testInvalidEncryptionKey() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("notebook.auth.mode", "no-oidc");
        env.setProperty("notebook.internalToken", "secret");
        env.setProperty("notebook.encryptionKey", "short-key");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
        assertEquals("notebook.encryptionKey must be 32 bytes base64", ex.getMessage());
    }

    @Test
    @DisplayName("T5.3: mode=ldap thiếu url")
    void testLdapMissingUrl() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("notebook.auth.mode", "ldap");
        env.setProperty("notebook.auth.ldap.service-account.username", "notebook-svc");
        env.setProperty("notebook.auth.ldap.service-account.password", "svc-pass");
        env.setProperty("notebook.encryptionKey", validKey());
        env.setProperty("notebook.internalToken", "secret");
        env.setProperty("notebook.auth.ldap.url", "");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
        assertEquals("notebook.auth.ldap.url is required", ex.getMessage());
    }

    @Test
    @DisplayName("T5.4: mode=ldap binddn và bindpw phải đi cùng nhau")
    void testLdapBindDnRequiresPassword() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("notebook.auth.mode", "ldap");
        env.setProperty("notebook.auth.ldap.service-account.username", "notebook-svc");
        env.setProperty("notebook.auth.ldap.service-account.password", "svc-pass");
        env.setProperty("notebook.internalToken", "secret");
        env.setProperty("notebook.auth.ldap.url", "ldaps://localhost:636");
        env.setProperty("notebook.auth.ldap.bind-dn", "uid=kyuubi,cn=users,cn=accounts,dc=lab,dc=com");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
        assertEquals("notebook.auth.ldap.bind-dn and bind-password must be set together", ex.getMessage());

        env.setProperty("notebook.auth.ldap.bind-password", "pw");
        assertDoesNotThrow(() -> AuthPropertiesValidator.validate(env));
    }

    @Test
    @DisplayName("T5.6: plain ldap:// thiếu startTls ngoài dev")
    void testLdapPlainWithoutStartTls() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("notebook.auth.mode", "ldap");
        env.setProperty("notebook.auth.ldap.service-account.username", "notebook-svc");
        env.setProperty("notebook.auth.ldap.service-account.password", "svc-pass");
        env.setProperty("notebook.encryptionKey", validKey());
        env.setProperty("notebook.internalToken", "secret");
        env.setProperty("notebook.auth.ldap.url", "ldap://localhost:389");
        env.setProperty("notebook.auth.ldap.userDnPattern", "uid={0},ou=people");
        env.setProperty("notebook.auth.ldap.startTls", "false");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
        assertEquals("plain ldap:// requires startTls=true outside dev", ex.getMessage());
    }

    @Test
    @DisplayName("T5.7: trustAll ngoài dev bị từ chối")
    void testLdapTrustAllOutsideDev() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("notebook.auth.mode", "ldap");
        env.setProperty("notebook.auth.ldap.service-account.username", "notebook-svc");
        env.setProperty("notebook.auth.ldap.service-account.password", "svc-pass");
        env.setProperty("notebook.encryptionKey", validKey());
        env.setProperty("notebook.internalToken", "secret");
        env.setProperty("notebook.auth.ldap.url", "ldaps://localhost:636");
        env.setProperty("notebook.auth.ldap.userDnPattern", "uid={0},ou=people");
        env.setProperty("notebook.auth.ldap.trustAll", "true");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
        assertEquals("trustAll is only allowed in dev", ex.getMessage());
    }

    @Test
    @DisplayName("T5.8: thiếu internalToken")
    void testMissingInternalToken() {
        MockEnvironment env = validOidcEnv();
        env.setProperty("notebook.internalToken", "");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
        assertEquals("NOTEBOOK_INTERNAL_TOKEN is required", ex.getMessage());
    }

    @Test
    @DisplayName("T5.9: cấm kyuubi.engine.spark.output.mode")
    void testForbiddenOutputMode() {
        MockEnvironment env = validOidcEnv();
        env.setProperty("kyuubi.session-configs.kyuubi.engine.spark.output.mode", "NOTEBOOK");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
        assertEquals("output.mode must stay at Kyuubi default (Q7)", ex.getMessage());
    }

    @Test
    @DisplayName("Cấu hình OIDC hợp lệ")
    void testValidOidcConfig() {
        assertDoesNotThrow(() -> AuthPropertiesValidator.validate(validOidcEnv()));
    }

    @Test
    void oidcRequiresEachEndpointAndSecret() {
        for (String key : new String[]{"jwt.issuer", "jwt.jwks-url", "ui.client-id", "ui.client-secret", "authorization-endpoint", "token-endpoint"}) {
            MockEnvironment env = validOidcEnv();
            env.setProperty("notebook.auth.oidc." + key, "");
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
            assertEquals("notebook.auth.oidc." + key + " is required for mode=oidc", ex.getMessage());
        }
    }

    @Test
    void oidcRequiresAudienceAndEncryptionKey() {
        MockEnvironment env = validOidcEnv();
        env.setProperty("notebook.auth.oidc.jwt.audience[0]", "");
        assertEquals("notebook.auth.oidc.jwt.audience is required for mode=oidc",
                assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env)).getMessage());

        MockEnvironment env2 = validOidcEnv();
        env2.setProperty("notebook.encryptionKey", "");
        assertEquals("notebook.encryptionKey must be 32 bytes base64",
                assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env2)).getMessage());
    }

    @Test
    void oidcRedirectUriMustTargetCallbackPath() {
        assertTrue(AuthPropertiesValidator.isValidRedirectUri("http://10.29.19.56:32380/api/v1/auth/callback"));
        assertTrue(AuthPropertiesValidator.isValidRedirectUri("https://notebook.lab.com/api/v1/auth/callback"));
        assertFalse(AuthPropertiesValidator.isValidRedirectUri("http://10.29.19.56:32379/api/v1/authentication/callback"));
        assertFalse(AuthPropertiesValidator.isValidRedirectUri("/api/v1/auth/callback"));
        assertFalse(AuthPropertiesValidator.isValidRedirectUri("javascript:alert(1)"));
        assertFalse(AuthPropertiesValidator.isValidRedirectUri("http://x/api/v1/auth/callback?next=/"));

        MockEnvironment env = validOidcEnv();
        env.setProperty("notebook.auth.oidc.redirect-uri", "http://10.29.19.56:32379/api/v1/authentication/callback");
        assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));

        env.setProperty("notebook.auth.oidc.redirect-uri", "");
        assertDoesNotThrow(() -> AuthPropertiesValidator.validate(env));
    }

    @Test
    void oidcEndpointsMustBeHttpUrls() {
        MockEnvironment env = validOidcEnv();
        env.setProperty("notebook.auth.oidc.token-endpoint", "keycloak:8080/token");
        assertEquals("notebook.auth.oidc.token-endpoint must be an http(s) URL",
                assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env)).getMessage());
    }

    @Test
    void ldapRequiresServiceAccountButNotEncryptionKey() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("notebook.auth.mode", "ldap");
        env.setProperty("notebook.internal-token", "t");
        env.setProperty("notebook.auth.ldap.url", "ldaps://localhost:636");
        env.setProperty("notebook.auth.ldap.user-dn-pattern", "uid={0},ou=people");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
        assertEquals("notebook.auth.ldap.service-account.username and password are required for mode=ldap", ex.getMessage());

        env.setProperty("notebook.auth.ldap.service-account.username", "notebook-svc");
        env.setProperty("notebook.auth.ldap.service-account.password", "svc-pass");
        assertDoesNotThrow(() -> AuthPropertiesValidator.validate(env));
    }
}
