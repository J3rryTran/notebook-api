package com.vtnexus.notebook.common.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class KyuubiAuthProbe implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KyuubiAuthProbe.class);

    private final AuthProperties authProperties;
    private final String kyuubiRestUrl;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kyuubi-auth-probe");
        t.setDaemon(true);
        return t;
    });

    public KyuubiAuthProbe(
            AuthProperties authProperties,
            @Value("${kyuubi.rest-url:http://localhost:10099}") String kyuubiRestUrl) {
        this.authProperties = authProperties;
        this.kyuubiRestUrl = kyuubiRestUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        probeKyuubi();
    }

    public void probeKyuubi() {
        String randomUser = "probe_" + UUID.randomUUID().toString().substring(0, 8);
        String randomPass = UUID.randomUUID().toString();
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (randomUser + ":" + randomPass).getBytes(StandardCharsets.UTF_8));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(kyuubiRestUrl + "/api/v1/ping"))
                    .header("Authorization", authHeader)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            // Mode LDAP: nếu Kyuubi trả 401 kèm WWW-Authenticate: Bearer -> không tương thích!
            if ("ldap".equalsIgnoreCase(authProperties.getMode())) {
                if (status == 401) {
                    String wwwAuth = response.headers().firstValue("WWW-Authenticate").orElse("");
                    if (wwwAuth.toLowerCase().contains("bearer")) {
                        throw new IllegalStateException("Kyuubi authentication mode is OIDC (requires Bearer token). It is incompatible with notebook.auth.mode=ldap. Please configure notebook.auth.mode=oidc.");
                    }
                }
            }

            // Nếu /ping với Basic ngẫu nhiên mà trả 200 (Kyuubi đang chạy chế độ NONE / unverified)
            if (status == 200) {
                if (!authProperties.isAllowUnverified()) {
                    throw new IllegalStateException("Kyuubi accepts unauthenticated ping (NONE mode), but notebook.auth.allow-unverified is false. Aborting startup to prevent unverified access.");
                } else {
                    log.warn("WARNING: Kyuubi accepts unauthenticated requests (NONE mode). allow-unverified is true; running in unverified mode.");
                    authProperties.setVerifiedOverride(false);

                    // WARN mỗi 5 phút
                    scheduler.scheduleAtFixedRate(() -> {
                        log.warn("WARNING: Kyuubi accepts unauthenticated requests (NONE mode). allow-unverified is true; running in unverified mode.");
                    }, 5, 5, TimeUnit.MINUTES);
                }
            }

        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            // Kyuubi không trả lời hoặc lỗi kết nối -> WARN, không chặn khởi động
            log.warn("Kyuubi auth probe could not connect to Kyuubi at {}: {}. Continuing startup.", kyuubiRestUrl, ex.getMessage());
        }
    }
}
