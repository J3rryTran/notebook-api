package com.vtnexus.notebook.common.datasource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public class DatasourceGuard implements EnvironmentPostProcessor, Ordered {

    public static final String REQUIRED_PREFIX = "jdbc:mariadb:";
    public static final String ERROR_MESSAGE = "notebook-api only supports MariaDB";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        validateUrl(url);
    }

    public static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("spring.datasource.url is required. " + ERROR_MESSAGE);
        }
        if (!url.trim().startsWith(REQUIRED_PREFIX)) {
            throw new IllegalStateException("Invalid datasource URL [" + url + "]: " + ERROR_MESSAGE);
        }
    }
}
