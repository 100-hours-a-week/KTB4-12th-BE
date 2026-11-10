package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.rabbitmq.client.Channel;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.recommendation.profiling.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingRequestMessage;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Data;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Error;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Outcome;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Payload;
import com.gift.gift.domain.recommendation.profiling.entity.ProfilingJob;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingJobRepository;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingMessageSerializer;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingResultMessageReader;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.messaging.RabbitManagementProperties;

import static com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingAmqpConfiguration.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "app.ai-profile.amqp.enabled=true", "app.ai-profile.result-consumer.enabled=true",
        "app.ai-profile.scheduling-enabled=false", "app.scheduling.enabled=false", "app.outbox.enabled=false",
        "spring.datasource.hikari.connection-timeout=1000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProfilingResultBrokerIntegrationTest {

    private static final GenericContainer<?> BROKER = new GenericContainer<>(new ImageFromDockerfile()
            .withFileFromPath("Dockerfile", Path.of("../infrastructure/rabbitmq/Dockerfile"))
            .withFileFromPath("rabbitmq.conf", Path.of("../infrastructure/rabbitmq/rabbitmq.conf"))
            .withFileFromPath("report-definitions.json", Path.of("../infrastructure/rabbitmq/report-definitions.json"))
            .withFileFromPath("profiling-definitions.json", Path.of("../infrastructure/rabbitmq/profiling-definitions.json")))
            .withCopyFileToContainer(MountableFile.forClasspathResource("rabbitmq/access-definitions.json"),
                    "/etc/rabbitmq/definitions/10-access.json")
            // 운영 기본 180초 대신 테스트 전용 5초로 DLQ 보존·복구 동작만 검증한다.
            .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS",
                    "-rabbit dead_letter_worker_publisher_confirm_timeout 5000")
            .withExposedPorts(5672, 15672)
            .waitingFor(Wait.forHttp("/api/overview").forPort(15672)
                    .withBasicCredentials("test", "test-password").withStartupTimeout(Duration.ofMinutes(2)));
    static { BROKER.start(); }

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.rabbitmq.host", BROKER::getHost);
        properties.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
        properties.add("spring.rabbitmq.username", () -> "test");
        properties.add("spring.rabbitmq.password", () -> "test-password");
        properties.add("spring.rabbitmq.virtual-host", () -> "gift");
        properties.add("app.rabbitmq.management.url", () -> "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(15672));
        properties.add("app.rabbitmq.management.username", () -> "test");
        properties.add("app.rabbitmq.management.password", () -> "test-password");
    }

    @Autowired private ProfilingResultListener listener;
    @Autowired private ProfilingResultConsumerConfiguration configuration;
    @Autowired private ProfilingResultMessageReader reader;
    @Autowired private ProfilingResultService service;
    @Autowired private ProfilingMessageSerializer serializer;
    @Autowired private UserRepository users;
    @Autowired private RecipientProfileRepository profiles;
    @Autowired private ProductRepository products;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private RabbitManagementProperties management;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private ApplicationEventMulticaster multicaster;
    @Autowired @Qualifier("rabbitConnectionFactory") private CachingConnectionFactory connection;
    @Autowired @Qualifier("profilingRabbitAdmin") private RabbitAdmin admin;
    @Autowired @Qualifier("profilingRabbitTemplate") private RabbitTemplate template;
    @Autowired @Qualifier("rabbitManagementRestClient") private RestClient managementClient;
    @MockitoSpyBean private ProfilingJobRepository jobs;

    private final List<ProfilingResultListener> extraListeners = new ArrayList<>();
    private final AtomicReference<AssertionError> observerFailure = new AtomicReference<>();
    private Long recipientId;
    private Long profileId;
    private Long rootId;
    private Long categoryId;
    private Long productId;
    private String requestId;

    @BeforeEach
    void setUp() {
        for (String queue : List.of(RESULT_QUEUE, RESULT_FAILED_QUEUE, REQUEST_FAILED_QUEUE)) {
            admin.purgeQueue(queue, false);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "$2a$10$" + "a".repeat(53),
                    "결과 큐 테스트", LocalDate.of(2000, 1, 1)));
            recipientId = user.getId();
            var profile = new RecipientProfile(user);
            profile.createNextSourceVersion();
            profile.markPending(LocalDateTime.now());
            profileId = profiles.saveAndFlush(profile).getId();
            Category root = new Category("root-" + UUID.randomUUID(), null);
            entityManager.persist(root);
            rootId = root.getId();
            Category category = new Category("child-" + UUID.randomUUID(), root);
            entityManager.persist(category);
            categoryId = category.getId();
            productId = products.saveAndFlush(new Product(category, "상품", "브랜드", null, BigDecimal.valueOf(1000), 10)).getId();
            var request = ProfilingRequestMessage.create(UUID.randomUUID(), Instant.now(),
                    new AiProfileRequest(recipientId, 1, List.of(), "MQ fixture", List.of()));
            requestId = jobs.saveAndFlush(ProfilingJob.create(profile, request, serializer)).getRequestId();
        });
        clearInvocations(jobs);
        await("결과 Consumer 등록", () -> consumers() == 1, 10);
    }

    @AfterEach
    void tearDown() {
        reset(jobs);
        for (var extra : extraListeners) {
            extra.stop();
            multicaster.removeApplicationListener(extra);
            extra.destroy();
        }
        listener.stop();
        await("결과 Consumer 종료", () -> consumers() == 0, 10);
        restorePolicies();
        admin.initialize();
        for (String queue : List.of(RESULT_QUEUE, RESULT_FAILED_QUEUE, REQUEST_FAILED_QUEUE)) {
            admin.purgeQueue(queue, false);
        }
        if (recipientId != null) {
            jdbc.update("DELETE FROM profiling_jobs WHERE profile_id=?", profileId);
            jdbc.update("DELETE FROM recipient_recommended_products WHERE recipient_id=?", recipientId);
            profiles.deleteById(profileId);
            products.deleteById(productId);
            jdbc.update("DELETE FROM categories WHERE id=?", categoryId);
            jdbc.update("DELETE FROM categories WHERE id=?", rootId);
            users.deleteById(recipientId);
        }
    }

    @Test
    @DisplayName("실제 DB 커밋 이후 ACK하며 동일 결과 재배달은 추천·작업·해시를 다시 저장하지 않는다")
    void commitsBeforeAckAndDeduplicatesRedelivery() throws Exception {
        CountDownLatch beforeCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object answer = callDefault(invocation);
            beforeCommit.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("test-commit-timeout");
            return answer;
        }).when(jobs).flush();
        AtomicInteger acknowledgements = new AtomicInteger();
        interceptAcks(listener, () -> {
            assertThat(job()).containsEntry("result_status", "COMPLETED");
            assertThat(job().get("result_fingerprint")).isNotNull();
        }, acknowledgements);
        try {
            send(success());
            assertThat(beforeCommit.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(acknowledgements.get()).isZero();
            assertThat(job()).containsEntry("result_status", "WAITING").containsEntry("result_fingerprint", null);
            assertThat(recommendationCount()).isZero();
        } finally {
            release.countDown();
        }
        await("커밋 후 ACK", () -> acknowledgements.get() == 1, 10);
        var before = job();
        send(success());
        await("중복 ACK", () -> acknowledgements.get() == 2, 10);
        assertThat(job()).isEqualTo(before);
        assertThat(recommendationCount()).isEqualTo(1);
        verify(jobs, times(1)).flush();
        assertThat(ready(RESULT_FAILED_QUEUE)).isZero();
        preflight().verify();
        assertThat(metadata(REQUEST_QUEUE).path("effective_policy_definition").has("dead-letter-exchange")).isFalse();
    }

    @Test
    @DisplayName("정상 FAILED 결과는 DB 실패로 기록하고 결과 DLQ로 보내지 않는다")
    void acknowledgesValidFinalFailure() throws Exception {
        AtomicInteger acknowledgements = new AtomicInteger();
        interceptAcks(listener, () -> assertThat(job()).containsEntry("result_status", "FAILED"), acknowledgements);
        var failure = new ProfilingResultMessage(1, requestId, Instant.now().toString(),
                new Payload(recipientId, 1, Outcome.FAILED, null, new Error("MODEL_FAILED", "처리 실패")));
        send(message(mapper.writeValueAsBytes(failure)));
        await("실패 결과 ACK", () -> acknowledgements.get() == 1, 10);
        assertThat(jdbc.queryForObject("SELECT profile_status FROM recipient_profiles WHERE id=?",
                String.class, profileId)).isEqualTo("FAILED");
        assertThat(ready(RESULT_FAILED_QUEUE)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"body", "identifier", "quarantine"})
    @DisplayName("본문·식별자 오류와 PR2 격리는 브로커 DLX로 보내고 정상 작업은 변경하지 않는다")
    void deadLettersOnlyUnprocessableMessages(String fault) throws Exception {
        var before = job();
        Message original = success();
        if (fault.equals("body")) {
            original = message("{".getBytes(StandardCharsets.UTF_8));
        } else if (fault.equals("identifier")) {
            original.getMessageProperties().setCorrelationId("different");
        } else {
            var unknown = new ProfilingResultMessage(1, UUID.randomUUID().toString(), Instant.now().toString(),
                    new Payload(recipientId, 1, Outcome.SUCCEEDED, new Data(List.of(productId)), null));
            original = message(mapper.writeValueAsBytes(unknown));
            original.getMessageProperties().setMessageId(unknown.requestId());
            original.getMessageProperties().setCorrelationId(unknown.requestId());
        }
        send(original);
        Message rejected = receiveDlq(10);
        assertThat(rejected.getBody()).isEqualTo(original.getBody());
        assertThat(rejected.getMessageProperties().getHeaders()).containsKey("x-death");
        assertThat(job()).isEqualTo(before);
        assertThat(listener.isHalted()).isFalse();
        send(success());
        await("후속 정상 결과", () -> "COMPLETED".equals(job().get("result_status")), 10);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("DB·미분류 오류는 조기 ACK/DLQ 이동 없이 한 번 중지하고 새 인스턴스에서 복구한다")
    void preservesUnackedResultAndStopsWithoutRecoveryLoop(boolean unclassified) throws Exception {
        RuntimeException failure = unclassified ? new IllegalStateException("test-unclassified")
                : new DataAccessResourceFailureException("test-db-write-failure");
        doAnswer(invocation -> {
            callDefault(invocation);
            throw failure;
        }).when(jobs).flush();
        send(success());
        await("오류 중지", listener::isHalted, 10);
        await("미ACK 재배달 보존", () -> consumers() == 0 && ready(RESULT_QUEUE) == 1, 10);
        var before = job();
        assertThat(before).containsEntry("result_status", "WAITING").containsEntry("result_fingerprint", null);
        assertThat(recommendationCount()).isZero();
        assertThat(ready(RESULT_FAILED_QUEUE)).isZero();
        verify(jobs, times(2)).flush();
        reset(jobs);
        newListener().start();
        await("복구 인스턴스 결과 반영", () -> "COMPLETED".equals(job().get("result_status")), 10);
        assertThat(recommendationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("커밋 후 ACK 통신 실패는 결과를 보존하고 재배달 때 중복 반영하지 않는다")
    void committedResultSurvivesAckFailure() throws Exception {
        AtomicInteger acknowledgements = new AtomicInteger();
        interceptAcks(listener, () -> {
            assertThat(job()).containsEntry("result_status", "COMPLETED");
            throw new IOException("test-ack-write-failure");
        }, acknowledgements);
        send(success());
        await("ACK 실패 중지", listener::isHalted, 10);
        await("ACK 실패 원본 재배달", () -> consumers() == 0 && ready(RESULT_QUEUE) == 1, 10);
        var committed = job();
        assertThat(acknowledgements.get()).isZero();
        assertThat(recommendationCount()).isEqualTo(1);
        var next = newListener();
        AtomicInteger nextAcks = new AtomicInteger();
        interceptAcks(next, () -> {}, nextAcks);
        next.start();
        await("중복 재배달 ACK", () -> nextAcks.get() == 1, 10);
        assertThat(job()).isEqualTo(committed);
        assertThat(recommendationCount()).isEqualTo(1);
        verify(jobs, times(1)).flush();
    }

    @Test
    @DisplayName("두 Consumer도 각각 prefetch1만 확보하며 동일 요청의 DB 반영은 한 번이다")
    void twoConsumersRespectPrefetchAndDatabaseIdempotency() throws Exception {
        CountDownLatch beforeCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object answer = callDefault(invocation);
            beforeCommit.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("test-prefetch-timeout");
            return answer;
        }).when(jobs).flush();
        var second = newListener();
        AtomicInteger acknowledgements = new AtomicInteger();
        interceptAcks(listener, () -> {}, acknowledgements);
        interceptAcks(second, () -> {}, acknowledgements);
        second.start();
        await("두 Consumer 등록", () -> consumers() == 2, 10);
        try {
            for (int i = 0; i < 6; i++) send(success());
            assertThat(beforeCommit.await(10, TimeUnit.SECONDS)).isTrue();
            await("두 건만 미ACK 확보", () -> ready(RESULT_QUEUE) == 4, 10);
            assertThat(acknowledgements.get()).isZero();
        } finally {
            release.countDown();
        }
        await("여섯 결과 정산", () -> acknowledgements.get() == 6, 10);
        assertThat(recommendationCount()).isEqualTo(1);
        verify(jobs, times(1)).flush();
    }

    @Test
    @DisplayName("DLQ가 사라진 동안 거절된 메시지는 브로커에 보존되고 대상 복구 후 전달된다")
    void preservesDeadLetterUntilTargetRecovers() throws Exception {
        // 최초 Publisher 연결 생성에 따른 Admin 재선언을 장애 주입 전에 완료한다.
        send(success());
        await("Publisher 연결 준비", () -> "COMPLETED".equals(job().get("result_status")), 10);
        admin.deleteQueue(RESULT_FAILED_QUEUE);
        Message invalid = message("{".getBytes(StandardCharsets.UTF_8));
        try {
            send(invalid);
            await("소스에서 거절 처리", () -> ready(RESULT_QUEUE) == 0, 10);
            Thread.sleep(1000);
            assertThat(admin.getQueueInfo(RESULT_FAILED_QUEUE)).isNull();
            assertThat(listener.isHalted()).isFalse();
        } finally {
            admin.initialize();
        }
        Message recovered = receiveDlq(20);
        assertThat(recovered.getBody()).isEqualTo(invalid.getBody());
        preflight().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"policy", "binding", "dlq", "permission", "operatorTtl"})
    @DisplayName("실제 브로커의 누락 선언은 복원·재검증하고 잘못된 Policy·권한·TTL은 시작을 차단한다")
    void rejectsUnsafeActualBrokerConfiguration(String fault) throws Exception {
        listener.stop();
        await("검증 전 소비 중지", () -> consumers() == 0, 10);
        try {
            switch (fault) {
                case "policy" -> {
                    var policy = policy("profiling-result-dead-lettering");
                    ((ObjectNode) policy.path("definition")).put("dead-letter-strategy", "at-most-once");
                    put("policies", "profiling-result-dead-lettering", policy);
                    await("Policy 적용", () -> "at-most-once".equals(metadata(RESULT_QUEUE)
                            .path("effective_policy_definition").path("dead-letter-strategy").asText()), 10);
                }
                case "binding" -> admin.removeBinding(BindingBuilder.bind(new Queue(RESULT_FAILED_QUEUE))
                        .to(new DirectExchange(EXCHANGE)).with(RESULT_FAILED_QUEUE));
                case "dlq" -> admin.deleteQueue(RESULT_FAILED_QUEUE);
                case "permission" -> put("permissions", "test", mapper.valueToTree(
                        Map.of("configure", ".*", "write", "^$", "read", ".*")));
                case "operatorTtl" -> put("operator-policies", "profiling-test-ttl", mapper.valueToTree(Map.of(
                        "pattern", "^profiling[.]result$", "apply-to", "quorum_queues", "priority", 200,
                        "definition", Map.of("message-ttl", 60000))));
                default -> throw new AssertionError(fault);
            }
            var candidate = newListener();
            if (fault.equals("binding") || fault.equals("dlq")) {
                assertThatThrownBy(() -> preflight().verify()).isInstanceOf(RuntimeException.class);
                candidate.start();
                await("누락 선언 복원 후 구독", () -> consumers() == 1, 10);
                assertThat(candidate.isHalted()).isFalse();
                preflight().verify();
            } else {
                candidate.start();
                await("비동기 구성 검증 실패 중지", candidate::isHalted, 10);
                assertThat(candidate.status()).contains("reason=CONFIGURATION");
                assertThat(consumers()).isZero();
            }
        } finally {
            put("permissions", "test", mapper.valueToTree(Map.of("configure", ".*", "write", ".*", "read", ".*")));
            if (fault.equals("operatorTtl")) {
                managementClient.delete().uri(api("operator-policies", "gift", "profiling-test-ttl")).retrieve().toBodilessEntity();
            }
            restorePolicies();
            admin.initialize();
        }
    }

    @Test
    @DisplayName("실제 MySQL 연결 차단 중 결과를 보존하고 DB 복구 후 같은 Consumer가 재개한다")
    void recoversAfterActualDatabaseOutage() throws Exception {
        var before = job();
        try {
            com.gift.gift.support.MySqlTestContainerInitializer.setOffline(true);
            send(success());
            await("실제 DB 장애 중지", () -> listener.status().contains("reason=TRANSIENT_DB"), 20);
            await("DB 장애 메시지 보존", () -> consumers() == 0 && ready(RESULT_QUEUE) == 1, 10);
            assertThat(ready(RESULT_FAILED_QUEUE)).isZero();
            listener.pause();
        } finally {
            com.gift.gift.support.MySqlTestContainerInitializer.setOffline(false);
        }
        assertThat(job()).isEqualTo(before);
        // 자동 점검 주기·연속 정상 조건은 시간 제어 단위 테스트에서 검증한다.
        await("Consumer 정리 완료", () -> listener.status().contains("stopping=false"), 10);
        listener.resume();
        await("DB 회복 후 결과 반영", () -> "COMPLETED".equals(job().get("result_status")), 10);
        await("복구 결과 ACK", () -> ready(RESULT_QUEUE) == 0 && consumers() == 1, 10);
        assertThat(recommendationCount()).isEqualTo(1);
        assertThat(job().get("result_fingerprint")).isNotNull();
        assertThat(ready(RESULT_FAILED_QUEUE)).isZero();
    }

    @Test
    @DisplayName("실제 RabbitMQ 중단·재시작 후 같은 Consumer가 재구독하고 결과를 한 번 반영한다")
    void recoversAfterActualBrokerRestart() throws Exception {
        try {
            var stopped = BROKER.execInContainer("rabbitmqctl", "stop_app");
            assertThat(stopped.getExitCode()).as("RabbitMQ 실제 중단").isZero();
            await("브로커 장애 중지", listener::isHalted, 20);
            assertThat(listener.status()).contains("reason=BROKER");
            assertThat(job()).containsEntry("result_status", "WAITING");
            listener.pause();
        } finally {
            var started = BROKER.execInContainer("rabbitmqctl", "start_app");
            assertThat(started.getExitCode()).as("RabbitMQ 복구").isZero();
            await("RabbitMQ 관리 API·Quorum 복구", () -> {
                try {
                    preflight().verify();
                    return true;
                } catch (RuntimeException notReady) {
                    return false;
                }
            }, 20);
        }
        admin.initialize();
        await("Consumer 정리 완료", () -> listener.status().contains("stopping=false"), 10);
        send(success());
        listener.resume();
        await("브로커 복구 후 결과 반영", () -> "COMPLETED".equals(job().get("result_status")), 10);
        await("브로커 복구 결과 ACK", () -> ready(RESULT_QUEUE) == 0 && consumers() == 1, 10);
        assertThat(recommendationCount()).isEqualTo(1);
        assertThat(ready(RESULT_FAILED_QUEUE)).isZero();
    }

    private ProfilingResultListener newListener() {
        var next = configuration.profilingResultListener(connection, admin, managementClient, management,
                reader, service, events, jdbc.getDataSource(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), java.time.Clock.systemUTC());
        multicaster.addApplicationListener(next);
        extraListeners.add(next);
        return next;
    }

    private SimpleMessageListenerContainer container(ProfilingResultListener owner) {
        return (SimpleMessageListenerContainer) ReflectionTestUtils.getField(owner, "container");
    }

    private void interceptAcks(ProfilingResultListener owner, CheckedRunnable beforeAck, AtomicInteger count) {
        var container = container(owner);
        var original = (ChannelAwareMessageListener) container.getMessageListener();
        container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
            Channel wrapped = mock(Channel.class, delegatesTo(channel));
            doAnswer(invocation -> {
                try {
                    beforeAck.run();
                } catch (AssertionError failure) {
                    observerFailure.set(failure);
                    throw new IllegalStateException("ACK 관측 검증 실패", failure);
                }
                channel.basicAck(invocation.getArgument(0), invocation.getArgument(1));
                count.incrementAndGet();
                return null;
            }).when(wrapped).basicAck(anyLong(), eq(false));
            original.onMessage(message, wrapped);
        });
    }

    private Message success() {
        return message(mapper.writeValueAsBytes(new ProfilingResultMessage(1, requestId, Instant.now().toString(),
                new Payload(recipientId, 1, Outcome.SUCCEEDED, new Data(List.of(productId)), null))));
    }

    private Message message(byte[] body) {
        var properties = new MessageProperties();
        properties.setMessageId(requestId);
        properties.setCorrelationId(requestId);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(body, properties);
    }

    private void send(Message message) throws Exception {
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        template.send(EXCHANGE, RESULT_ROUTING_KEY, message, correlation);
        assertThat(correlation.getFuture().get(10, TimeUnit.SECONDS).ack()).isTrue();
        assertThat(correlation.getReturned()).isNull();
    }

    private Message receiveDlq(int seconds) throws Exception {
        AtomicReference<Message> received = new AtomicReference<>();
        await("DLQ 전달", () -> {
            received.set(template.receive(RESULT_FAILED_QUEUE));
            return received.get() != null;
        }, seconds);
        return received.get();
    }

    private int consumers() {
        QueueInformation info = admin.getQueueInfo(RESULT_QUEUE);
        return info == null ? 0 : info.getConsumerCount();
    }

    private long ready(String queue) {
        QueueInformation info = admin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }

    private Map<String, Object> job() {
        return jdbc.queryForMap("SELECT * FROM profiling_jobs WHERE request_id=?", requestId);
    }

    private int recommendationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM recipient_recommended_products WHERE recipient_id=?",
                Integer.class, recipientId);
    }

    private ProfilingResultTopologyPreflight preflight() {
        return new ProfilingResultTopologyPreflight(managementClient, management.url(), "gift", "test");
    }

    private JsonNode metadata(String queue) {
        URI uri = org.springframework.web.util.UriComponentsBuilder.fromUri(api("queues", "gift", queue))
                .queryParam("disable_stats", true).build().toUri();
        return managementClient.get().uri(uri).retrieve().body(JsonNode.class);
    }

    private URI api(String... segments) {
        return org.springframework.web.util.UriComponentsBuilder.fromUri(management.url()).pathSegment("api")
                .pathSegment(segments).build().encode().toUri();
    }

    private void put(String resource, String name, JsonNode body) {
        managementClient.put().uri(api(resource, "gift", name)).body(body).retrieve().toBodilessEntity();
    }

    private ObjectNode policy(String name) throws IOException {
        JsonNode definitions = mapper.readTree(java.nio.file.Files.readString(
                Path.of("../infrastructure/rabbitmq/profiling-definitions.json")));
        for (JsonNode policy : definitions.path("policies")) {
            if (name.equals(policy.path("name").asText())) {
                var copy = (ObjectNode) policy.deepCopy();
                copy.remove("name");
                copy.remove("vhost");
                return copy;
            }
        }
        throw new IllegalArgumentException("테스트 Policy가 없습니다.");
    }

    private void restorePolicies() {
        try {
            put("policies", "profiling-result-dead-lettering", policy("profiling-result-dead-lettering"));
            put("policies", "profiling-failed-queue-protection", policy("profiling-failed-queue-protection"));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Object callDefault(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
        return mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }

    private void await(String description, BooleanSupplier condition, int seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            assertThat(observerFailure.get()).as("Listener 스레드의 ACK 관측 오류").isNull();
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
