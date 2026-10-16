package com.vtnexus.notebook.store.service;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.vtnexus.notebook.common.auth.AuthProperties;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LdapAuthenticatorTest {

    private InMemoryDirectoryServer ldapServer;
    private AuthProperties authProperties;
    private AuthProperties.LdapProperties ldap;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=lab,dc=com");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0));
        config.setSchema(null);
        ldapServer = new InMemoryDirectoryServer(config);
        ldapServer.startListening();

        ldapServer.add("dn: dc=lab,dc=com", "objectClass: domain", "dc: lab");
        ldapServer.add("dn: cn=accounts,dc=lab,dc=com", "objectClass: nsContainer", "cn: accounts");
        ldapServer.add("dn: cn=users,cn=accounts,dc=lab,dc=com", "objectClass: nsContainer", "cn: users");
        ldapServer.add("dn: cn=groups,cn=accounts,dc=lab,dc=com", "objectClass: nsContainer", "cn: groups");
        user("kyuubi", "kyuubi-pass");
        user("alice", "password123");
        user("bob", "bobpassword");
        ldapServer.add("dn: cn=notebook-users,cn=groups,cn=accounts,dc=lab,dc=com",
                "objectClass: groupOfNames", "cn: notebook-users",
                "member: uid=alice,cn=users,cn=accounts,dc=lab,dc=com");

        authProperties = new AuthProperties();
        authProperties.setMode("ldap");
        ldap = authProperties.getLdap();
        ldap.setUrl("ldap://localhost:" + ldapServer.getListenPort());
        ldap.setBaseDn("cn=accounts,dc=lab,dc=com");
        ldap.setGuidKey("uid");
        ldap.setUserDnPattern("uid=%s,cn=users,cn=accounts,dc=lab,dc=com");
        ldap.setGroupDnPattern("cn=%s,cn=groups,cn=accounts,dc=lab,dc=com");
        ldap.setGroupClassKey("groupOfNames");
        ldap.setGroupMembershipKey("member");
        environment = new MockEnvironment();
    }

    private void user(String uid, String password) throws Exception {
        ldapServer.add("dn: uid=" + uid + ",cn=users,cn=accounts,dc=lab,dc=com",
                "objectClass: person", "objectClass: inetOrgPerson",
                "uid: " + uid, "cn: " + uid, "sn: " + uid, "userPassword: " + password);
    }

    private void useBindUser() {
        ldap.setBindDn("uid=kyuubi,cn=users,cn=accounts,dc=lab,dc=com");
        ldap.setBindPassword("kyuubi-pass");
    }

    private LdapAuthenticator authenticator() {
        return new LdapAuthenticator(authProperties, environment);
    }

    @AfterEach
    void tearDown() {
        if (ldapServer != null) {
            ldapServer.shutDown(true);
        }
    }

    @Test
    void directBindWithUserDnPatternLikeKyuubiWithoutBindUser() {
        assertThat(authenticator().authenticate("alice", "password123").canonicalUsername()).isEqualTo("alice");
        assertThatThrownBy(() -> authenticator().authenticate("alice", "wrong"))
                .isInstanceOf(NotebookApiException.class).extracting("code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void bindUserThenRebindAsUserLikeKyuubi() {
        useBindUser();
        assertThat(authenticator().authenticate("bob", "bobpassword").canonicalUsername()).isEqualTo("bob");
        assertThatThrownBy(() -> authenticator().authenticate("bob", "wrong"))
                .isInstanceOf(NotebookApiException.class).extracting("code").isEqualTo("INVALID_CREDENTIALS");
        assertThatThrownBy(() -> authenticator().authenticate("ghost", "x"))
                .isInstanceOf(NotebookApiException.class).extracting("code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void canonicalNameComesFromGuidKey() {
        useBindUser();
        assertThat(authenticator().authenticate("ALICE", "password123").canonicalUsername()).isEqualTo("alice");
    }

    @Test
    void multipleUserDnPatternsSeparatedByColon() {
        ldap.setUserDnPattern("uid=%s,cn=staged,cn=accounts,dc=lab,dc=com:uid=%s,cn=users,cn=accounts,dc=lab,dc=com");
        assertThat(authenticator().authenticate("alice", "password123").canonicalUsername()).isEqualTo("alice");
    }

    @Test
    void groupFilterUsesGroupMembershipKey() {
        useBindUser();
        ldap.setGroupFilter(List.of("notebook-users"));
        assertThat(authenticator().authenticate("alice", "password123").canonicalUsername()).isEqualTo("alice");
        assertThatThrownBy(() -> authenticator().authenticate("bob", "bobpassword"))
                .isInstanceOf(NotebookApiException.class).extracting("code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void userFilterAllowsOnlyListedUsers() {
        useBindUser();
        ldap.setUserFilter(List.of("bob"));
        assertThat(authenticator().authenticate("bob", "bobpassword").canonicalUsername()).isEqualTo("bob");
        assertThatThrownBy(() -> authenticator().authenticate("alice", "password123"))
                .isInstanceOf(NotebookApiException.class).extracting("code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void customLdapQueryFilter() {
        useBindUser();
        ldap.setCustomLdapQuery("(&(objectClass=person)(uid=alice))");
        assertThat(authenticator().authenticate("alice", "password123").canonicalUsername()).isEqualTo("alice");
        assertThatThrownBy(() -> authenticator().authenticate("bob", "bobpassword"))
                .isInstanceOf(NotebookApiException.class).extracting("code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void injectionAndEmptyPasswordRejected() {
        useBindUser();
        assertThatThrownBy(() -> authenticator().authenticate("*)(uid=*", "x"))
                .isInstanceOf(NotebookApiException.class).extracting("code").isEqualTo("INVALID_CREDENTIALS");
        assertThatThrownBy(() -> authenticator().authenticate("alice", ""))
                .isInstanceOf(NotebookApiException.class).extracting("code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void lookupUserForRecheckNeedsBindUser() {
        assertThat(authenticator().lookupUser("alice").canonicalUsername()).isEqualTo("alice");

        useBindUser();
        assertThat(authenticator().lookupUser("alice").canonicalUsername()).isEqualTo("alice");
        assertThatThrownBy(() -> authenticator().lookupUser("ghost"))
                .isInstanceOf(NotebookApiException.class).extracting("code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void candidatePrincipalsFollowKyuubi() {
        LdapAuthenticator a = authenticator();
        assertThat(a.createCandidatePrincipals("alice")).containsExactly("uid=alice,cn=users,cn=accounts,dc=lab,dc=com");
        assertThat(a.createCandidatePrincipals("alice@lab.com")).containsExactly("alice@lab.com");
        ldap.setDomain("lab.com");
        assertThat(a.createCandidatePrincipals("alice")).containsExactly("alice@lab.com");
        ldap.setDomain("");
        ldap.setUserDnPattern("");
        assertThat(a.parseDnPatterns("")).containsExactly("uid=%s,cn=accounts,dc=lab,dc=com");
    }

    @Test
    void serverDownReturns503() {
        ldapServer.shutDown(true);
        assertThatThrownBy(() -> authenticator().authenticate("alice", "password123"))
                .isInstanceOf(NotebookApiException.class).extracting("code").isEqualTo("AUTH_BACKEND_UNAVAILABLE");
    }
}
