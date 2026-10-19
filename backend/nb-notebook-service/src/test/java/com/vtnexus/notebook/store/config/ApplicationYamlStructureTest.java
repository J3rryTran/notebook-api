package com.vtnexus.notebook.store.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationYamlStructureTest {

    @Test
    void springBlocksAreUnderSpring() throws Exception {
        PropertySource<?> yml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).get(0);
        assertThat(yml.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(true);
        assertThat(yml.getProperty("spring.flyway.baseline-version")).isEqualTo(1);
        assertThat(yml.getProperty("spring.datasource.password")).hasToString("${SPRING_DATASOURCE_PASSWORD:}");
        assertThat(yml.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(yml.containsProperty("logging.flyway.enabled")).isFalse();
        assertThat(yml.getProperty("notebook.auth.ldap.bind-password")).hasToString("${NOTEBOOK_LDAP_BIND_PASSWORD:}");
    }
}
