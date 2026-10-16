package com.vtnexus.notebook.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesBindingTest {

    @Test
    void notebookImportMaxSizeBindsDespiteJavaKeyword() {
        NotebookProperties props = new NotebookProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(
                "notebook.import.max-size", "1234",
                "notebook.document.max-size", "5678")))
                .bind("notebook", Bindable.ofInstance(props));

        assertThat(props.getImport().getMaxSize()).isEqualTo(1234);
        assertThat(props.getDocument().getMaxSize()).isEqualTo(5678);
    }

    @Test
    void kyuubiIdleSettingsBind() {
        KyuubiProperties props = new KyuubiProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(
                "kyuubi.idle-session-timeout", "0s",
                "kyuubi.idle-reaper-interval", "60000")))
                .bind("kyuubi", Bindable.ofInstance(props));

        assertThat(props.getIdleSessionTimeout()).isEqualTo(Duration.ZERO);
        assertThat(props.getIdleReaperInterval()).isEqualTo(60000L);
    }

    @Test
    void ldapKeysRenderedByChartBind() {
        com.vtnexus.notebook.common.auth.AuthProperties props = new com.vtnexus.notebook.common.auth.AuthProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(
                "notebook.auth.ldap.bind-dn", "uid=kyuubi,cn=users,cn=accounts,dc=lab,dc=com",
                "notebook.auth.ldap.user-dn-pattern", "uid=%s,cn=users,cn=accounts,dc=lab,dc=com",
                "notebook.auth.ldap.group-filter[0]", "notebook-users",
                "notebook.auth.ldap.custom-ldap-query", "(uid=%s)",
                "notebook.auth.ldap.service-account.username", "kyuubi")))
                .bind("notebook.auth", Bindable.ofInstance(props));

        assertThat(props.getLdap().getBindDn()).isEqualTo("uid=kyuubi,cn=users,cn=accounts,dc=lab,dc=com");
        assertThat(props.getLdap().getUserDnPattern()).isEqualTo("uid=%s,cn=users,cn=accounts,dc=lab,dc=com");
        assertThat(props.getLdap().getGroupFilter()).containsExactly("notebook-users");
        assertThat(props.getLdap().getCustomLdapQuery()).isEqualTo("(uid=%s)");
        assertThat(props.getLdap().getServiceAccount().getUsername()).isEqualTo("kyuubi");
    }
}
