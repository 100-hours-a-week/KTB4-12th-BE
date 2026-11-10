package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.profiling.scheduler.ProfilingPublishMonitor;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("monitoring")
@AutoConfigureMetrics
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "management.server.address=0.0.0.0",
        "MONITORING_PASSWORD=profiling-monitoring-test-password-32-characters",
        "app.scheduling.enabled=false", "app.ai-profile.scheduling-enabled=true",
        "app.ai-profile.amqp.enabled=true", "app.outbox.enabled=false"
})
class ProfilingObservabilityIntegrationTest {
    private static final Network NETWORK = Network.newNetwork();
    private static final GenericContainer<?> BROKER = new GenericContainer<>("rabbitmq:4.4.0-management")
            .withNetwork(NETWORK).withNetworkAliases("rabbitmq")
            .withEnv("RABBITMQ_DEFAULT_USER", "profiling").withEnv("RABBITMQ_DEFAULT_PASS", "test-password")
            .withEnv("RABBITMQ_DEFAULT_VHOST", "profiling").withExposedPorts(5672, 15672)
            .waitingFor(Wait.forHttp("/api/overview").forPort(15672)
                    .withBasicCredentials("profiling", "test-password").withStartupTimeout(Duration.ofMinutes(2)));
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PASSWORD = "profiling-monitoring-test-password-32-characters";
    private static final String BODY = "{\"payload\":\"계측 검증용 고정 입력\"}";

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        BROKER.start();
        registry.add("spring.rabbitmq.host", BROKER::getHost);
        registry.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "profiling");
        registry.add("spring.rabbitmq.password", () -> "test-password");
        registry.add("spring.rabbitmq.virtual-host", () -> "profiling");
    }

    @Autowired private ProfilingJobPublisher publisher;
    @Autowired private ProfilingPublishMonitor monitor;
    @Autowired private MeterRegistry registry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private RecipientProfileRepository profiles;
    @Autowired @Qualifier("profilingRabbitAdmin") private RabbitAdmin admin;
    @Value("${local.management.port}") private int managementPort;

    @AfterAll
    static void stopBroker() {
        BROKER.close();
        NETWORK.close();
    }

    @Test
    void realPublisherMetrics_areScrapedAndBlockedAlertIsDeliveredAndResolved() throws Exception {
        Path directory = Files.createTempDirectory("profiling-observability-");
        List<String> notifications = new CopyOnWriteArrayList<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        receiver.createContext("/alerts", exchange -> {
            notifications.add(new String(exchange.getRequestBody().readNBytes(1024 * 1024), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        Testcontainers.exposeHostPorts(managementPort, receiver.getAddress().getPort());
        Binding binding = new Binding(ProfilingAmqpConfiguration.REQUEST_QUEUE, Binding.DestinationType.QUEUE,
                ProfilingAmqpConfiguration.EXCHANGE, ProfilingAmqpConfiguration.REQUEST_ROUTING_KEY, null);
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53), "지표검증", LocalDate.of(2000, 1, 1)));
        RecipientProfile profile = profiles.saveAndFlush(new RecipientProfile(user));
        try {
            var plugin = BROKER.execInContainer("rabbitmq-plugins", "enable", "rabbitmq_prometheus");
            assertThat(plugin.getExitCode()).as(plugin.getStderr()).isZero();
            Files.writeString(directory.resolve("alertmanager.yml"), """
                    route:
                      receiver: local-verification
                      group_by: [alertname]
                      group_wait: 10s
                      group_interval: 10s
                      repeat_interval: 4h
                    receivers:
                      - name: local-verification
                        webhook_configs:
                          - url: http://host.testcontainers.internal:%d/alerts
                            send_resolved: true
                    """.formatted(receiver.getAddress().getPort()));
            Path rules = Path.of("../monitoring/profiling-alerts.yml").toAbsolutePath().normalize();
            assertThat(rules).exists();
            try (var alertmanager = new GenericContainer<>("prom/alertmanager:v0.34.2")
                    .withNetwork(NETWORK).withNetworkAliases("alertmanager")
                    .withCopyFileToContainer(MountableFile.forHostPath(directory.resolve("alertmanager.yml")), "/etc/alertmanager/alertmanager.yml")
                    .withCommand("--config.file=/etc/alertmanager/alertmanager.yml", "--cluster.listen-address=")
                    .withExposedPorts(9093).waitingFor(Wait.forHttp("/-/ready").forPort(9093))) {
                alertmanager.start();
                Files.writeString(directory.resolve("prometheus.yml"), """
                        global:
                          scrape_interval: 1s
                          evaluation_interval: 1s
                        rule_files: [/etc/prometheus/profiling-alerts.yml]
                        alerting:
                          alertmanagers:
                            - static_configs:
                                - targets: ['alertmanager:9093']
                        scrape_configs:
                          - job_name: spring
                            metrics_path: /actuator/prometheus
                            basic_auth: {username: prometheus, password: '%s'}
                            static_configs:
                              - targets: ['host.testcontainers.internal:%d']
                          - job_name: rabbitmq
                            static_configs:
                              - targets: ['rabbitmq:15692']
                          - job_name: rabbitmq-queues
                            metrics_path: /metrics/detailed
                            params: {family: [queue_coarse_metrics, queue_consumer_count], vhost: [profiling]}
                            static_configs:
                              - targets: ['rabbitmq:15692']
                        """.formatted(PASSWORD, managementPort));
                try (var prometheus = new GenericContainer<>("prom/prometheus:v3.15.0").withNetwork(NETWORK)
                        .withCopyFileToContainer(MountableFile.forHostPath(directory.resolve("prometheus.yml")), "/etc/prometheus/prometheus.yml")
                        .withCopyFileToContainer(MountableFile.forHostPath(rules), "/etc/prometheus/profiling-alerts.yml")
                        .withExposedPorts(9090).waitingFor(Wait.forHttp("/-/ready").forPort(9090))) {
                    prometheus.start();
                    monitor.refresh();
                    await("all real scrape targets UP", Duration.ofSeconds(45), () ->
                            scalar(prometheus, "sum(up)") == 3);
                    String normal = insertJob(profile.getId(), 1);
                    publisher.publishOne();
                    assertThat(jdbc.queryForObject("SELECT publish_status FROM profiling_jobs WHERE request_id=?", String.class, normal))
                            .isEqualTo("PUBLISHED");
                    await("ACK and message-size metrics", Duration.ofSeconds(15), () ->
                            scalar(prometheus, "profiling_publish_outcomes_total{code=\"ACK_NO_RETURN\"}") == 1
                                    && scalar(prometheus, "profiling_publish_body_bytes_sum") == BODY.getBytes(StandardCharsets.UTF_8).length);
                    assertThat(scalar(prometheus, "profiling_publish_confirm_wait_seconds_count")).isEqualTo(1);
                    await("RabbitMQ Ready gauge", Duration.ofSeconds(20), () ->
                            scalar(prometheus, "rabbitmq_detailed_queue_messages_ready{queue=\"profiling.request\"}") == 1);
                    assertThat(scalar(prometheus, "rabbitmq_process_resident_memory_bytes")).isPositive();
                    assertThat(scalar(prometheus, "rabbitmq_disk_space_available_bytes")).isPositive();

                    admin.removeBinding(binding);
                    insertJob(profile.getId(), 2);
                    publisher.publishOne();
                    monitor.refresh();
                    await("BLOCKED alert delivered", Duration.ofSeconds(100), () ->
                            received(notifications, "ProfilingPublishBlocked", "firing"));
                    assertThat(scalar(prometheus, "profiling_publish_outcomes_total{code=\"UNROUTABLE_MESSAGE\"}")).isEqualTo(1);
                    admin.declareBinding(binding);
                    // A controlled repair of the fixture clears the condition; manual recovery is PR4-1's concern.
                    jdbc.update("UPDATE profiling_jobs SET result_status='SUPERSEDED' WHERE profile_id=? AND publish_status='BLOCKED'", profile.getId());
                    monitor.refresh();
                    await("BLOCKED alert resolved notification", Duration.ofSeconds(45), () ->
                            received(notifications, "ProfilingPublishBlocked", "resolved"));
                    Path report = Path.of("build/reports/profiling-observability.txt");
                    Files.createDirectories(report.getParent());
                    Files.writeString(report, "Real MySQL + RabbitMQ 4.4 + management endpoint + Prometheus + Alertmanager verified.\n"
                            + "targets UP=3; ACK=1; Return=1; Ready=1; BLOCKED firing and resolved delivered to local HTTP receiver.\n"
                            + "body_bytes=" + BODY.getBytes(StandardCharsets.UTF_8).length + "\n"
                            + "confirm_wait_sum_seconds=" + scalar(prometheus, "profiling_publish_confirm_wait_seconds_sum") + "\n"
                            + "No production workload, actual AI consumer, or external notification channel tested.\n");
                    var validation = prometheus.execInContainer("promtool", "check", "config", "/etc/prometheus/prometheus.yml");
                    assertThat(validation.getExitCode()).as(validation.getStdout() + validation.getStderr()).isZero();
                }
            }
        } finally {
            receiver.stop(0);
            admin.declareBinding(binding);
            jdbc.update("DELETE FROM profiling_jobs WHERE profile_id=?", profile.getId());
            profiles.deleteById(profile.getId());
            users.deleteById(user.getId());
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private String insertJob(Long profileId, int version) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO profiling_jobs (request_id,profile_id,source_version,request_body,created_at,updated_at) VALUES (?,?,?,?,NOW(6),NOW(6))",
                id, profileId, version, BODY);
        return id;
    }

    private static double scalar(GenericContainer<?> prometheus, String query) {
        try {
            String uri = "http://" + prometheus.getHost() + ":" + prometheus.getMappedPort(9090)
                    + "/api/v1/query?query=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            var response = HTTP.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode results = JSON.readTree(response.body()).path("data").path("result");
            return results.isEmpty() ? Double.NaN : Double.parseDouble(results.get(0).path("value").get(1).asText());
        } catch (Exception exception) {
            throw new IllegalStateException("Prometheus query failed", exception);
        }
    }

    private static boolean received(List<String> notifications, String alertName, String status) {
        return notifications.stream().anyMatch(body -> {
            for (JsonNode alert : JSON.readTree(body).path("alerts")) {
                if (alertName.equals(alert.path("labels").path("alertname").asText())
                        && status.equals(alert.path("status").asText())) {
                    return true;
                }
            }
            return false;
        });
    }

    private static void await(String condition, Duration timeout, BooleanSupplier predicate) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!predicate.getAsBoolean()) {
            assertThat(System.nanoTime()).as(condition).isLessThan(deadline);
            Thread.sleep(500);
        }
    }
}
