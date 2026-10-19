package com.vtnexus.notebook.execution.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretEnvMappingTest {

    @Test
    void ldapSecretsAreReadFromChartEnv() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        PropertySource<?> yml = sources.get(0);
        assertThat(yml.getProperty("notebook.auth.ldap.bind-password"))
                .hasToString("${NOTEBOOK_LDAP_BIND_PASSWORD:}");
        assertThat(yml.getProperty("notebook.auth.ldap.service-account.password"))
                .hasToString("${NOTEBOOK_LDAP_SERVICE_ACCOUNT_PASSWORD:}");
        assertThat(yml.getProperty("spring.datasource.password")).hasToString("${SPRING_DATASOURCE_PASSWORD:}");
        assertThat(yml.getProperty("spring.flyway.enabled")).isEqualTo(false);
        assertThat(yml.containsProperty("logging.datasource.password")).isFalse();
    }
}
