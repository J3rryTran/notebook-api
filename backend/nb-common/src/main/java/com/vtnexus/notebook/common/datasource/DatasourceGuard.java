package com.vtnexus.notebook.common.datasource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

public class DatasourceGuard implements EnvironmentPostProcessor {

    public static final String REQUIRED_PREFIX = "jdbc:mariadb:";
    public static final String ERROR_MESSAGE = "notebook-api only supports MariaDB";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Chỉ kiểm tra khi có cấu hình datasource hoặc khi context load datasource
        String url = environment.getProperty("spring.datasource.url");
        if (url != null) {
            validateUrl(url);
        }
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
