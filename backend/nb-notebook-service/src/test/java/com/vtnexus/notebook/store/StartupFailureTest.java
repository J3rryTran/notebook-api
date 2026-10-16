package com.vtnexus.notebook.store;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupFailureTest {

    @Test
    void testStartupFailsWhenAuthModeIsInvalid() {
        assertThatThrownBy(() -> {
            new SpringApplicationBuilder(TestApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(
                            "--spring.datasource.url=jdbc:mariadb://localhost:3306/test",
                            "--notebook.auth.mode=abc",
                            "--notebook.internalToken=secret-token"
                    );
        }).hasMessageContaining("notebook.auth.mode must be one of oidc, no-oidc, ldap");
    }

    @Test
    void testStartupFailsWhenInternalTokenIsMissing() {
        assertThatThrownBy(() -> {
            new SpringApplicationBuilder(TestApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(
                            "--spring.datasource.url=jdbc:mariadb://localhost:3306/test",
                            "--notebook.auth.mode=oidc"
                    );
        }).hasMessageContaining("NOTEBOOK_INTERNAL_TOKEN is required");
    }

    @Test
    void testStartupFailsWhenDatasourceUrlIsNotMariaDB() {
        assertThatThrownBy(() -> {
            new SpringApplicationBuilder(TestApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(
                            "--spring.datasource.url=jdbc:postgresql://localhost:5432/notebook",
                            "--notebook.auth.mode=oidc",
                            "--notebook.internalToken=secret-token"
                    );
        }).hasMessageContaining("Invalid datasource URL scheme [jdbc:postgresql:]: notebook-api only supports MariaDB");
    }

    @Test
    void testStartupFailsWhenDatasourceUrlIsMissing() {
        assertThatThrownBy(() -> {
            new SpringApplicationBuilder(TestApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(
                            "--spring.datasource.url=",
                            "--notebook.auth.mode=oidc",
                            "--notebook.internalToken=secret-token"
                    );
        }).hasMessageContaining("spring.datasource.url is required. notebook-api only supports MariaDB");
    }

    @Test
    void testStartupFailsWhenLdapModeWithoutUrl() {
        assertThatThrownBy(() -> {
            new SpringApplicationBuilder(TestApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(
                            "--spring.datasource.url=jdbc:mariadb://localhost:3306/test",
                            "--notebook.auth.mode=ldap",
                            "--notebook.auth.ldap.service-account.username=kyuubi",
                            "--notebook.auth.ldap.service-account.password=svc-pass",
                            "--notebook.internalToken=secret-token"
                    );
        }).hasMessageContaining("notebook.auth.ldap.url is required");
    }

    static class TestApplication {}
}
