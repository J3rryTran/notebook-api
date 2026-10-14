package com.vtnexus.notebook.store.service;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.vtnexus.notebook.common.auth.AuthProperties;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LdapAuthenticatorTest {

    private InMemoryDirectoryServer ldapServer;
    private int ldapPort;
    private AuthProperties authProperties;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=Directory Manager", "password");

        InMemoryListenerConfig listenerConfig = InMemoryListenerConfig.createLDAPConfig("default", 0);
        config.setListenerConfigs(listenerConfig);
        config.setSchema(null); // allow flexible schema for in-memory test

        ldapServer = new InMemoryDirectoryServer(config);
        ldapServer.startListening();
        ldapPort = ldapServer.getListenPort();

        // Populate entries:
        // Base DN
        ldapServer.add("dn: dc=example,dc=com", "objectClass: top", "objectClass: domain", "dc: example");
        ldapServer.add("dn: ou=people,dc=example,dc=com", "objectClass: top", "objectClass: organizationalUnit", "ou: people");
        ldapServer.add("dn: ou=groups,dc=example,dc=com", "objectClass: top", "objectClass: organizationalUnit", "ou: groups");

        // Alice (admin)
        ldapServer.add(
                "dn: uid=alice,ou=people,dc=example,dc=com",
                "objectClass: top",
                "objectClass: person",
                "objectClass: inetOrgPerson",
                "uid: alice",
                "cn: Alice Walker",
                "sn: Walker",
                "userPassword: password123"
        );

        // Bob (regular user)
        ldapServer.add(
                "dn: uid=bob,ou=people,dc=example,dc=com",
                "objectClass: top",
                "objectClass: person",
                "objectClass: inetOrgPerson",
                "uid: bob",
                "cn: Bob Builder",
                "sn: Builder",
                "userPassword: bobpassword"
        );

        // Group notebook-admins containing alice
        ldapServer.add(
                "dn: cn=notebook-admins,ou=groups,dc=example,dc=com",
                "objectClass: top",
                "objectClass: groupOfNames",
                "cn: notebook-admins",
                "member: uid=alice,ou=people,dc=example,dc=com"
        );

        authProperties = new AuthProperties();
        authProperties.setMode("ldap");
        AuthProperties.LdapProperties ldapProps = authProperties.getLdap();
        ldapProps.setUrl("ldap://localhost:" + ldapPort);
        ldapProps.setBaseDn("dc=example,dc=com");
        ldapProps.setUserSearchBase("ou=people");
        ldapProps.setUserSearchFilter("(uid={0})");
        ldapProps.setUsernameAttribute("uid");
        ldapProps.setGroupSearchBase("ou=groups");
        ldapProps.setGroupMemberAttribute("member");
        ldapProps.setAdminGroup("cn=notebook-admins,ou=groups,dc=example,dc=com");
        ldapProps.setManagerDn("cn=Directory Manager");
        ldapProps.setManagerPassword("password");

        environment = new MockEnvironment();
    }

    @AfterEach
    void tearDown() {
        if (ldapServer != null) {
            ldapServer.shutDown(true);
        }
    }

    @Test
    void testUserSearchAliceAdminAndBobNotAdmin() {
        LdapAuthenticator authenticator = new LdapAuthenticator(authProperties, environment);

        // ALICE viết hoa -> trả về canonical 'alice' và admin=true
        LdapAuthenticator.LdapUser alice = authenticator.authenticate("ALICE", "password123");
        assertThat(alice.canonicalUsername()).isEqualTo("alice");
        assertThat(alice.isAdmin()).isTrue();

        // bob -> admin=false
        LdapAuthenticator.LdapUser bob = authenticator.authenticate("bob", "bobpassword");
        assertThat(bob.canonicalUsername()).isEqualTo("bob");
        assertThat(bob.isAdmin()).isFalse();
    }

    @Test
    void testUserDnPatternConfiguration() {
        authProperties.getLdap().setUserDnPattern("uid={0},ou=people");
        LdapAuthenticator authenticator = new LdapAuthenticator(authProperties, environment);

        LdapAuthenticator.LdapUser alice = authenticator.authenticate("alice", "password123");
        assertThat(alice.canonicalUsername()).isEqualTo("alice");
        assertThat(alice.isAdmin()).isTrue();

        LdapAuthenticator.LdapUser bob = authenticator.authenticate("bob", "bobpassword");
        assertThat(bob.canonicalUsername()).isEqualTo("bob");
        assertThat(bob.isAdmin()).isFalse();
    }

    @Test
    void testWrongPasswordReturns401() {
        LdapAuthenticator authenticator = new LdapAuthenticator(authProperties, environment);

        assertThatThrownBy(() -> authenticator.authenticate("alice", "wrong-password"))
                .isInstanceOf(NotebookApiException.class)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    void testEmptyPasswordReturns401() {
        LdapAuthenticator authenticator = new LdapAuthenticator(authProperties, environment);

        assertThatThrownBy(() -> authenticator.authenticate("alice", ""))
                .isInstanceOf(NotebookApiException.class)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    void testLdapInjectionAttemptsReturn401() {
        LdapAuthenticator authenticator = new LdapAuthenticator(authProperties, environment);

        // Injection username: *)(uid=*
        assertThatThrownBy(() -> authenticator.authenticate("*)(uid=*", "password"))
                .isInstanceOf(NotebookApiException.class)
                .hasMessageContaining("Invalid credentials");

        // Malformed username: alice,ou=x
        assertThatThrownBy(() -> authenticator.authenticate("alice,ou=x", "password"))
                .isInstanceOf(NotebookApiException.class)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    void testServerDownReturns503() {
        LdapAuthenticator authenticator = new LdapAuthenticator(authProperties, environment);

        // Tắt server
        ldapServer.shutDown(true);

        assertThatThrownBy(() -> authenticator.authenticate("alice", "password123"))
                .isInstanceOf(NotebookApiException.class)
                .hasMessageContaining("LDAP unavailable")
                .extracting("code")
                .isEqualTo("AUTH_BACKEND_UNAVAILABLE");
    }
}
