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
        MockEnvironment env = new MockEnvironment();
        env.setProperty("notebook.auth.mode", "oidc");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
        assertEquals("NOTEBOOK_INTERNAL_TOKEN is required", ex.getMessage());
    }

    @Test
    @DisplayName("T5.9: cấm kyuubi.engine.spark.output.mode")
    void testForbiddenOutputMode() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("notebook.auth.mode", "oidc");
        env.setProperty("notebook.internalToken", "secret");
        env.setProperty("kyuubi.session-configs.kyuubi.engine.spark.output.mode", "NOTEBOOK");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AuthPropertiesValidator.validate(env));
        assertEquals("output.mode must stay at Kyuubi default (Q7)", ex.getMessage());
    }

    @Test
    @DisplayName("Cấu hình OIDC hợp lệ")
    void testValidOidcConfig() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("notebook.auth.mode", "oidc");
        env.setProperty("notebook.internalToken", "secret-token-123");

        assertDoesNotThrow(() -> AuthPropertiesValidator.validate(env));
    }

    @org.junit.jupiter.api.Test
    void oidcRedirectPathMustBeLocalNonApiPath() {
        assertTrue(AuthPropertiesValidator.isValidRedirectPath("/auth/callback"));
        assertTrue(AuthPropertiesValidator.isValidRedirectPath("/notebook/oidc-callback"));
        org.junit.jupiter.api.Assertions.assertFalse(AuthPropertiesValidator.isValidRedirectPath("auth/callback"));
        org.junit.jupiter.api.Assertions.assertFalse(AuthPropertiesValidator.isValidRedirectPath("//evil.example/cb"));
        org.junit.jupiter.api.Assertions.assertFalse(AuthPropertiesValidator.isValidRedirectPath("https://evil.example/cb"));
        org.junit.jupiter.api.Assertions.assertFalse(AuthPropertiesValidator.isValidRedirectPath("/api/v1/cb"));
        org.junit.jupiter.api.Assertions.assertFalse(AuthPropertiesValidator.isValidRedirectPath("/internal/cb"));
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
