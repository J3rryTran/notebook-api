package com.vtnexus.notebook.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EmptyMapToListConverterTest {

    @Configuration
    @EnableConfigurationProperties
    @Import({KyuubiProperties.class, EmptyMapToListConverter.class})
    static class Config {
    }

    private ApplicationContextRunner runner(String yaml) {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> {
                    try {
                        PropertySource<?> ps = new YamlPropertySourceLoader().load("chart",
                                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8))).get(0);
                        ctx.getEnvironment().getPropertySources().addFirst(ps);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withUserConfiguration(Config.class);
    }

    @Test
    void emptyMapBindsAsEmptyList() {
        runner("kyuubi:\n  rest-url: http://k:10099\n  allowed-session-config-keys: {}\n")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(KyuubiProperties.class).getAllowedSessionConfigKeys()).isEmpty();
                });
    }

    @Test
    void listStillBinds() {
        runner("kyuubi:\n  allowed-session-config-keys: [spark.executor.memory]\n")
                .run(ctx -> assertThat(ctx.getBean(KyuubiProperties.class).getAllowedSessionConfigKeys())
                        .containsExactly("spark.executor.memory"));
    }
}
