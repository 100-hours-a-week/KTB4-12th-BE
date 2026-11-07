package com.gift.gift.domain.recommendation.profiling.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.gift.gift.domain.preference.entity.UserGiftPreference;
import com.gift.gift.domain.preference.repository.UserGiftPreferenceRepository;
import com.gift.gift.domain.recommendation.profiling.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.profiling.entity.*;
import com.gift.gift.domain.recommendation.profiling.repository.*;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingRecoveryService.*;
import com.gift.gift.domain.recommendation.profiling.support.ProfileRequestFactory;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingAmqpConfiguration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
        "app.scheduling.enabled=false", "app.ai-profile.scheduling-enabled=false",
        "app.ai-profile.amqp.enabled=true", "app.outbox.enabled=false"
})
class ProfilingRecoveryServiceIntegrationTest {
    private static final GenericContainer<?> BROKER = new GenericContainer<>("rabbitmq:4.4.0-management")
            .withEnv("RABBITMQ_DEFAULT_USER", "test").withEnv("RABBITMQ_DEFAULT_PASS", "test-password")
            .withExposedPorts(5672).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    static { BROKER.start(); }

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.rabbitmq.host", BROKER::getHost);
        properties.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
        properties.add("spring.rabbitmq.username", () -> "test");
        properties.add("spring.rabbitmq.password", () -> "test-password");
        properties.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    @Autowired ProfilingRecoveryService recovery;
    @Autowired ProfilingJobPreparationService preparation;
    @Autowired ProfileDispatchService dispatch;
    @Autowired RecipientProfileRepository profiles;
    @MockitoSpyBean ProfilingJobRepository jobs;
    @MockitoSpyBean ProfileRequestFactory factory;
    @Autowired UserRepository users;
    @Autowired UserGiftPreferenceRepository preferences;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;
    @Autowired @Qualifier("profilingRabbitTemplate") RabbitTemplate rabbit;
    private TransactionTemplate tx;
    private Long userId;
    private Long profileId;
    private String requestId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            User user = users.saveAndFlush(new User("recovery-" + UUID.randomUUID() + "@example.com",
                    "$2a$10$" + "a".repeat(53), "복구 테스트", LocalDate.of(1990, 1, 1)));
            userId = user.getId();
            var profile = new RecipientProfile(user);
            profile.recordPreferenceChange(LocalDateTime.now(clock).minusDays(1));
            profileId = profiles.saveAndFlush(profile).getId();
            preferences.saveAndFlush(new UserGiftPreference(user, "original-private-input"));
        });
        requestId = preparation.prepare(profileId).orElseThrow();
        jdbc.update("""
                UPDATE profiling_jobs SET publish_status = 'BLOCKED', attempt_count = 3,
                    first_failed_at = UTC_TIMESTAMP(6), next_attempt_at = UTC_TIMESTAMP(6),
                    last_error_code = 'PUBLISH_BUDGET_EXHAUSTED', updated_at = UTC_TIMESTAMP(6)
                WHERE request_id = ?
                """, requestId);
        while (rabbit.receive(ProfilingAmqpConfiguration.REQUEST_QUEUE) != null) { }
    }

    @AfterEach
    void tearDown() {
        reset(jobs, factory);
        jdbc.update("DELETE FROM profiling_jobs WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM user_gift_preferences WHERE user_id = ?", userId);
        profiles.deleteById(profileId);
        users.deleteById(userId);
        while (rabbit.receive(ProfilingAmqpConfiguration.REQUEST_QUEUE) != null) { }
    }

    @Test
    @DisplayName("동일 입력 재개는 새 변경을 보존하고 기존 발행 루프로 같은 본문을 전달한다")
    void resumesSameSnapshotThroughExistingPublisher(CapturedOutput output) {
        var before = jobs.findById(requestId).orElseThrow();
        changeInput();
        var command = command(Action.RESUME_SAME);
        recovery.recover(command);
        var resumed = jobs.findById(requestId).orElseThrow();
        assertThat(resumed.getRequestBody()).isEqualTo(before.getRequestBody());
        assertThat(resumed.getSourceVersion()).isEqualTo(1);
        assertThat(resumed.getPublishStatus()).isEqualTo(ProfilingPublishStatus.READY);
        assertThat(resumed.getAttemptCount()).isZero();
        assertThat(resumed.getFirstFailedAt()).isNull();
        assertThat(resumed.getNextAttemptAt()).isNull();
        assertThat(profiles.findById(profileId).orElseThrow().getLastChangedAt()).isNotNull();
        assertThat(rabbit.receive(ProfilingAmqpConfiguration.REQUEST_QUEUE)).isNull();
        assertThatThrownBy(() -> recovery.recover(command)).isInstanceOf(IllegalStateException.class);
        assertThat(output).contains("PROFILING_RECOVERY_COMMITTED", "previousAttempts=3", "operator=test-operator");
        dispatch.dispatchDueProfiles();
        assertPublished(requestId, before.getRequestBody());
    }

    @Test
    @DisplayName("최신 입력 대체는 새 요청·버전을 생성하고 이전 본문을 보존한다")
    void replacesWithLatestInputAndPublishesNewVersion(CapturedOutput output) {
        String beforeBody = jobs.findById(requestId).orElseThrow().getRequestBody();
        changeInput();
        var oldCommand = command(Action.REPLACE_LATEST);
        var result = recovery.recover(oldCommand);
        assertThat(result.nextRequestId()).isNotEqualTo(requestId);
        var previous = jobs.findById(requestId).orElseThrow();
        var next = jobs.findById(result.nextRequestId()).orElseThrow();
        assertThat(previous.getRequestBody()).isEqualTo(beforeBody);
        assertThat(previous.getResultStatus()).isEqualTo(ProfilingResultStatus.SUPERSEDED);
        assertThat(previous.getFinishedAt()).isNotNull();
        assertThat(next.getSourceVersion()).isEqualTo(2);
        assertThat(next.getRequestBody()).contains("latest-private-input");
        assertThat(profiles.findById(profileId).orElseThrow().getLastChangedAt()).isNull();
        assertThatThrownBy(() -> recovery.recover(oldCommand)).isInstanceOf(IllegalStateException.class);
        assertThat(output).contains("PROFILING_RECOVERY_COMMITTED", "nextVersion=2");
        dispatch.dispatchDueProfiles();
        assertPublished(next.getRequestId(), next.getRequestBody());
        assertThat(rabbit.receive(ProfilingAmqpConfiguration.REQUEST_QUEUE)).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ProfilingPublishStatus.class, names = "BLOCKED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("BLOCKED 외의 발행 상태는 재개하지 않고 감사 성공 로그도 남기지 않는다")
    void rejectsNonBlockedPublication(ProfilingPublishStatus state, CapturedOutput output) {
        jdbc.update("UPDATE profiling_jobs SET publish_status = ? WHERE request_id = ?", state.name(), requestId);
        var before = snapshot();
        assertThatThrownBy(() -> recovery.recover(command(Action.RESUME_SAME)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(output).doesNotContain("PROFILING_RECOVERY_COMMITTED");
    }

    @ParameterizedTest
    @EnumSource(value = ProfilingResultStatus.class, names = "WAITING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("이미 종료된 결과 상태는 재개하지 않는다")
    void rejectsTerminalResult(ProfilingResultStatus state) {
        jdbc.update("UPDATE profiling_jobs SET result_status = ?, finished_at = UTC_TIMESTAMP(6) WHERE request_id = ?",
                state.name(), requestId);
        var before = snapshot();
        assertThatThrownBy(() -> recovery.recover(command(Action.RESUME_SAME)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("잘못된 요청 ID·수정 시각·선점·발행 확인과 이미 반영된 결과를 거부한다")
    void rejectsStaleOrInconsistentTargets(CapturedOutput output) {
        var c = command(Action.RESUME_SAME);
        assertThatThrownBy(() -> recovery.recover(new Command(c.action(), c.profileId(), UUID.randomUUID().toString(),
                c.sourceVersion(), c.expectedUpdatedAt(), c.operator(), c.reason())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> recovery.recover(new Command(c.action(), c.profileId(), c.requestId(),
                c.sourceVersion(), c.expectedUpdatedAt().minusSeconds(1), c.operator(), c.reason())))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE profiling_jobs SET claim_token = ?, claimed_at = UTC_TIMESTAMP(6) WHERE request_id = ?",
                UUID.randomUUID().toString(), requestId);
        assertThatThrownBy(() -> recovery.recover(command(Action.RESUME_SAME))).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE profiling_jobs SET claim_token = NULL, claimed_at = NULL, published_at = UTC_TIMESTAMP(6) WHERE request_id = ?", requestId);
        assertThatThrownBy(() -> recovery.recover(command(Action.RESUME_SAME))).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE profiling_jobs SET published_at = NULL WHERE request_id = ?", requestId);
        tx.executeWithoutResult(status -> profiles.findByIdForUpdate(profileId).orElseThrow().markCompleted(1));
        assertThatThrownBy(() -> recovery.recover(command(Action.RESUME_SAME))).isInstanceOf(IllegalStateException.class);
        assertThat(output).doesNotContain("PROFILING_RECOVERY_COMMITTED");
    }

    @Test
    @DisplayName("동시 재개는 한 번만 성공한다")
    void concurrentRecoverySucceedsOnce(CapturedOutput output) throws Exception {
        var command = command(Action.RESUME_SAME);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            try { recovery.recover(command); return true; }
            catch (IllegalStateException rejected) { return false; }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(output.getOut().split("PROFILING_RECOVERY_COMMITTED", -1)).hasSize(2);
    }

    @Test
    @DisplayName("작업 저장 실패는 버전·대체 처리를 롤백하고 성공 감사 로그를 남기지 않는다")
    void insertionFailureRollsBackEverything(CapturedOutput output) {
        var command = command(Action.REPLACE_LATEST);
        var before = snapshot();
        doThrow(new DataIntegrityViolationException("simulated insertion failure"))
                .when(jobs).saveAndFlush(any(ProfilingJob.class));
        assertThatThrownBy(() -> recovery.recover(command)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(profiles.findById(profileId).orElseThrow().getSourceVersion()).isEqualTo(1);
        assertThat(output).doesNotContain("PROFILING_RECOVERY_COMMITTED");
    }

    @Test
    @DisplayName("최신 입력 크기 초과는 이전 작업과 버전을 보존한다")
    void oversizedReplacementRollsBack(CapturedOutput output) {
        var before = snapshot();
        doAnswer(invocation -> new AiProfileRequest(userId, invocation.getArgument(1, Long.class),
                List.of(), "한".repeat(90_000), List.of())).when(factory).create(any(), eq(2L));
        assertThatThrownBy(() -> recovery.recover(command(Action.REPLACE_LATEST)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(profiles.findById(profileId).orElseThrow().getSourceVersion()).isEqualTo(1);
        assertThat(output).doesNotContain("PROFILING_RECOVERY_COMMITTED");
    }

    private Command command(Action action) {
        var job = jobs.findById(requestId).orElseThrow();
        return new Command(action, profileId, requestId, job.getSourceVersion(), job.getUpdatedAt(),
                "test-operator", "broker configuration restored");
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("SELECT * FROM profiling_jobs WHERE profile_id = ? ORDER BY source_version", profileId);
    }

    private void changeInput() {
        tx.executeWithoutResult(status -> {
            profiles.findByIdForUpdate(profileId).orElseThrow().recordPreferenceChange(LocalDateTime.now(clock));
            preferences.findByUser_IdAndDeletedAtIsNull(userId).orElseThrow().changePreference("latest-private-input");
        });
    }

    private void assertPublished(String id, String body) {
        var message = rabbit.receive(ProfilingAmqpConfiguration.REQUEST_QUEUE, 5000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(id);
        assertThat(message.getBody()).isEqualTo(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(jobs.findById(id).orElseThrow().getPublishStatus()).isEqualTo(ProfilingPublishStatus.PUBLISHED);
    }
}
