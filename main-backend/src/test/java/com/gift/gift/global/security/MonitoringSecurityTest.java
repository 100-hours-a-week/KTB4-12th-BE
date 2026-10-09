package com.gift.gift.global.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("monitoring")
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "MONITORING_PASSWORD=monitoring-test-password-32-characters",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"
})
class MonitoringSecurityTest {

    private static final String PASSWORD = "monitoring-test-password-32-characters";
    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int appPort;

    @Value("${local.management.port}")
    private int managementPort;

    @Test
    @DisplayName("메트릭은 별도 관리 포트에서 모니터링 자격 증명으로만 조회한다")
    void metrics_requireMonitoringCredentialsOnManagementPort() throws Exception {
        assertThat(get(managementPort, "/actuator/prometheus", null).statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/prometheus", "wrong").statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/prometheus", PASSWORD).statusCode()).isEqualTo(200);
        assertThat(get(appPort, "/actuator/prometheus", PASSWORD).statusCode()).isEqualTo(403);
        assertThat(get(managementPort, "/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get(appPort, "/users/me", PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("HTTP 히스토그램과 JVM 및 Hikari 메트릭을 실제 응답에서 확인한다")
    void metrics_exposeHttpHistogramJvmAndHikari() throws Exception {
        get(appPort, "/products", null);
        String metrics = get(managementPort, "/actuator/prometheus", PASSWORD).body();
        assertThat(metrics).contains("http_server_requests_seconds_bucket{", "uri=\"/products\"");
        assertThat(metrics).contains("jvm_memory_used_bytes", "jvm_threads_live_threads");
        assertThat(metrics).contains("hikaricp_connections_active", "hikaricp_connections_pending");
    }

    private HttpResponse<String> get(int port, String path, String password) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (password != null) {
            String credentials = Base64.getEncoder().encodeToString(
                    ("prometheus:" + password).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + credentials);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
