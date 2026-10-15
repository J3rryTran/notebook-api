package com.vtnexus.notebook.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kyuubi")
public class KyuubiProperties {

    private String restUrl = "http://localhost:10099";
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(60);
    private Duration sessionOpenTimeout = Duration.ofMinutes(10);
    private Duration idleSessionTimeout = Duration.ofMinutes(30);
    private boolean pinToInstance = true;
    private List<String> allowedSessionConfigKeys = new ArrayList<>(List.of(
            "spark.executor.memory",
            "spark.executor.cores",
            "spark.sql.shuffle.partitions"
    ));
    private Map<String, String> sessionConfigs = new HashMap<>();
}
