package com.gift.gift.domain.bugreport.messaging;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.messaging.*;
import com.gift.gift.global.outbox.OutboxClaimRepository;
import com.gift.gift.global.outbox.OutboxClaimRepository.Job;
import com.gift.gift.global.outbox.OutboxProperties;
import com.gift.gift.global.outbox.OutboxRelay;

import static com.gift.gift.domain.bugreport.messaging.ReportMqProperties.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportMqBrokerIntegrationTest {
    private final GenericContainer<?> broker = new GenericContainer<>(new ImageFromDockerfile()
            .withFileFromPath("Dockerfile", Path.of("../infrastructure/rabbitmq/Dockerfile"))
            .withFileFromPath("rabbitmq.conf", Path.of("../infrastructure/rabbitmq/rabbitmq.conf"))
            .withFileFromPath("report-definitions.json", Path.of("../infrastructure/rabbitmq/report-definitions.json")))
            .withCopyFileToContainer(MountableFile.forClasspathResource("rabbitmq/access-definitions.json"),
                    "/etc/rabbitmq/definitions/10-access.json")
            .withExposedPorts(5672, 15672)
            .waitingFor(Wait.forHttp("/api/overview").forPort(15672)
                    .withBasicCredentials("test", "test-password").withStartupTimeout(Duration.ofMinutes(2)));
    private final ObjectMapper mapper = new ObjectMapper();
    private final OutboxProperties settings = new OutboxProperties(10, 60, 5000, 10, 2, 300);
    private final RabbitRoutingProperties routing = new RabbitRoutingProperties("existing.search", "search.changed");
    private CachingConnectionFactory connection;
    private RabbitTemplate template;
    private RabbitAdmin admin;
    private RabbitPublisher publisher;
    private ReportTopologyPreflight preflight;
    private String managementUrl;

    @BeforeAll
    void startBroker() throws Exception {
        broker.start();
        connection = new CachingConnectionFactory(broker.getHost(), broker.getMappedPort(5672));
        connection.setUsername("test");
        connection.setPassword("test-password");
        connection.setVirtualHost("gift");
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        template = new RabbitPublisherConfiguration().outboxRabbitTemplate(connection);
        admin = new RabbitAdmin(connection);
        admin.declareExchange(new DirectExchange("existing.search", true, false));
        admin.declareQueue(QueueBuilder.durable("existing.profiling").quorum().build());
        managementUrl = "http://" + broker.getHost() + ":" + broker.getMappedPort(15672);
        preflight = new ReportTopologyPreflight(new RabbitManagementConfiguration()
                .rabbitManagementRestClient(new RabbitManagementProperties(
                        URI.create(managementUrl), "test", "test-password")),
                URI.create(managementUrl), "gift");
        awaitPreflight();
        publisher = new RabbitPublisher(template, mapper, settings, routing);
    }

    @BeforeEach
    void provisionAndPurge() throws Exception {
        var result = broker.execInContainer("rabbitmqctl", "import_definitions",
                "/etc/rabbitmq/definitions/00-report.json");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        awaitPreflight();
        for (String queue : List.of(DISCORD_QUEUE, SHEETS_QUEUE, RETRY_QUEUE, DLQ)) {
            admin.purgeQueue(queue);
        }
    }

    private void awaitPreflight() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            try {
                preflight.verify();
                return;
            } catch (IllegalStateException exception) {
                if (System.nanoTime() >= deadline) throw exception;
                Thread.sleep(200);
            }
        }
    }

    @AfterAll
    void stopBroker() {
        if (connection != null) connection.destroy();
        broker.stop();
    }

    private MessageEnvelope event() {
        return new MessageEnvelope(UUID.randomUUID().toString(), EVENT_TYPE, 1, "report-key", "REPORT", "42",
                null, mapper.readTree("{\"description\":\"test\"}"), LocalDateTime.now(), null);
    }

    @Test
    @DisplayName("배포 정의는 타 도메인을 보존하고 최초 두 채널 분배 및 실제 30초 후 실패 채널 복귀를 보장한다")
    void fansOutAndReturnsOnlyToFailedChannelAfterThirtySeconds() throws Exception {
        var existingQueue = RestClient.builder().defaultHeaders(h -> h.setBasicAuth("test", "test-password"))
                .build().get().uri(URI.create(managementUrl + "/api/queues/gift/existing.profiling"))
                .retrieve().body(tools.jackson.databind.JsonNode.class);
        assertThat(existingQueue.path("type").asText()).isEqualTo("quorum");
        var envelope = event();
        assertThat(
                publisher.publish(envelope, "initial", EVENTS, CREATED).get(10, TimeUnit.SECONDS).confirmed()).isTrue();
        var discord = template.receive(DISCORD_QUEUE, 3000);
        var sheets = template.receive(SHEETS_QUEUE, 3000);
        assertThat(discord).isNotNull();
        assertThat(sheets).isNotNull();
        assertThat(discord.getBody()).isEqualTo(sheets.getBody());
        assertThat(discord.getMessageProperties().getMessageId()).isEqualTo(envelope.eventId());
        assertThat(discord.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        long started = System.nanoTime();
        assertThat(publisher.publish(envelope, "retry", RETRY, DISCORD).get(10, TimeUnit.SECONDS).confirmed()).isTrue();
        var retried = template.receive(DISCORD_QUEUE, 35000);
        assertThat(retried).isNotNull();
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isGreaterThanOrEqualTo(29000);
        assertThat(retried.getMessageProperties().getMessageId()).isEqualTo(envelope.eventId());
        assertThat(retried.getBody()).isEqualTo(discord.getBody());
        assertThat(template.receive(SHEETS_QUEUE, 100)).isNull();
        assertThat(publisher.publish(envelope, "dead", DLX, DISCORD).get(10, TimeUnit.SECONDS).confirmed()).isTrue();
        assertThat(template.receive(DLQ, 3000)).isNotNull();
    }

    @Test
    @DisplayName("한 채널 binding 누락은 mandatory 성공이어도 preflight에서 거부한다")
    void missingInitialBindingBlocksRelayBeforeClaim() {
        admin.removeBinding(new Binding(SHEETS_QUEUE, Binding.DestinationType.QUEUE, EVENTS, CREATED, null));
        assertThatThrownBy(preflight::verify).isInstanceOf(IllegalStateException.class);
        OutboxRelay relay = mock(OutboxRelay.class);
        new ReportOutboxRelay(relay, preflight,
                new ReportMqProperties(10)).relayReports();
        verifyNoInteractions(relay);
    }

    @Test
    @DisplayName("일부 큐 포화 reject는 다른 큐에 도착했어도 발행 완료로 기록하지 않는다")
    void partialRejectDoesNotCompleteOutbox() throws Exception {
        var result = broker.execInContainer("rabbitmqctl", "set_policy", "-p", "gift", "report-test-full",
                "^Report[.]sheets$", "{\"max-length\":0,\"overflow\":\"reject-publish\"}",
                "--apply-to", "queues", "--priority", "100");
        assertThat(result.getExitCode()).isZero();
        try {
            preflight.verify();
            OutboxClaimRepository repository = mock(OutboxClaimRepository.class);
            Job job = new Job(1, "partial", event());
            when(repository.claim(EVENT_TYPE, 10)).thenReturn(List.of(job));
            new OutboxRelay(repository, publisher, settings, routing).relay(EVENT_TYPE, 10, EVENTS, CREATED);
            verify(repository).fail(job, "BrokerNack");
            verify(repository, never()).complete(any());
            assertThat(template.receive(DISCORD_QUEUE, 3000)).isNotNull();
            assertThat(template.receive(SHEETS_QUEUE, 100)).isNull();
        } finally {
            assertThat(broker.execInContainer("rabbitmqctl", "clear_policy", "-p", "gift", "report-test-full")
                    .getExitCode()).isZero();
        }
    }

    @Test
    @DisplayName("미라우팅 return은 공통 Relay의 실패로 기록하고 PUBLISHED로 완료하지 않는다")
    void returnedPublishDoesNotCompleteOutbox() {
        OutboxClaimRepository repository = mock(OutboxClaimRepository.class);
        Job job = new Job(2, "return", event());
        when(repository.claim(EVENT_TYPE, 10)).thenReturn(List.of(job));
        new OutboxRelay(repository, publisher, settings, routing).relay(EVENT_TYPE, 10, EVENTS, "unbound");
        verify(repository).fail(job, "Unroutable");
        verify(repository, never()).complete(any());
    }
}
