package com.gift.gift.domain.recommendation.profiling.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingAmqpConfiguration;
import com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingJobPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.preference.repository.UserGiftPreferenceRepository;
import com.gift.gift.domain.preference.service.PreferenceSaveService;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.repository.CategoryRepository;
import com.gift.gift.domain.recommendation.chat.repository.AiChatTurnUsageRepository;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.infrastructure.ai.profiling.AiProfilingClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("integration")
@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "app.ai-profile.scheduling-enabled=false",
        "app.ai-profile.amqp.enabled=true",
        "app.outbox.enabled=false",
        "app.ai-profile.quiet-period=1h",
        "app.ai-profile.maximum-window=6h"
})
class ProfileDispatchServiceIntegrationTest {
    private static final GenericContainer<?> BROKER = new GenericContainer<>("rabbitmq:4.4.0-management")
            .withEnv("RABBITMQ_DEFAULT_USER", "test")
            .withEnv("RABBITMQ_DEFAULT_PASS", "test-password")
            .withExposedPorts(5672).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    static { BROKER.start(); }

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", BROKER::getHost);
        registry.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "test");
        registry.add("spring.rabbitmq.password", () -> "test-password");
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    @Autowired @Qualifier("profilingRabbitTemplate") RabbitTemplate template;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ProfilingJobPublisher publisher;


    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW =
            Instant.parse("2026-09-27T03:00:00Z");

    @Autowired
    private ProfileDispatchService profileDispatchService;

    @Autowired
    private PreferenceSaveService preferenceSaveService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private RecipientProfileRepository profileRepository;

    @Autowired
    private UserDislikeCategoryRepository dislikeRepository;

    @Autowired
    private UserGiftPreferenceRepository giftPreferenceRepository;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private CreditAccountRepository creditAccountRepository;

    @MockitoBean
    private AiProfilingClient aiProfilingClient;

    @Autowired
    private AiChatTurnUsageRepository aiChatTurnUsageRepository;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        clearTestData();

        reset(aiProfilingClient);
        reset(publisher);

        setCurrentTime(NOW);
    }

    @AfterEach
    void tearDown() {
        verifyNoInteractions(aiProfilingClient);
        clearTestData();
    }

    private void clearTestData() {
        while (template.receive(ProfilingAmqpConfiguration.REQUEST_QUEUE) != null) { }
        jdbc.update("DELETE FROM profiling_jobs");
        aiChatTurnUsageRepository.deleteAll();
        profileRepository.deleteAll();
        dislikeRepository.deleteAll();
        giftPreferenceRepository.deleteAll();
        categoryRepository.deleteAll();
        creditTransactionRepository.deleteAll();
        creditAccountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("마지막 변경 후 1시간이 지난 프로파일을 요청한다")
    void dispatchDueProfiles_requestsAfterQuietPeriod() {
        User user = saveChangedPreferenceAt(NOW.minusSeconds(3_601));

        setCurrentTime(NOW);
        profileDispatchService.dispatchDueProfiles();

        assertPublished(user);
        assertThat(findProfile(user).getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
    }

    @Test
    @DisplayName("최초 변경 후 6시간이 지난 프로파일은 최근 변경이 있어도 요청한다")
    void dispatchDueProfiles_requestsAfterMaximumWindow() {
        User user = saveChangedPreferenceAt(NOW.minusSeconds(6 * 3_600));
        Category second = saveRootCategory("식품");
        setCurrentTime(NOW.minusSeconds(30 * 60));
        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(second.getId())
        );

        setCurrentTime(NOW);
        profileDispatchService.dispatchDueProfiles();

        assertPublished(user);
        assertThat(findProfile(user).getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
    }

    @Test
    @DisplayName("디바운스와 최대 대기 조건을 모두 충족하지 않으면 요청하지 않는다")
    void dispatchDueProfiles_skipsProfileBeforeThresholds() {
        saveChangedPreferenceAt(NOW.minusSeconds(30 * 60));
        setCurrentTime(NOW);

        profileDispatchService.dispatchDueProfiles();

        assertThat(template.receive(ProfilingAmqpConfiguration.REQUEST_QUEUE)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profiling_jobs", Integer.class)).isZero();
    }

    @Test
    @DisplayName("MQ 발행 전 작업·버전이 커밋되고 발행 호출에는 DB 트랜잭션이 없다")
    void dispatchDueProfiles_commitsVersionBeforeCallingAiOutsideTransaction() {
        User user = saveChangedPreferenceAt(NOW.minusSeconds(3_601));
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        AtomicBoolean versionCommitted = new AtomicBoolean(false);

        doAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            versionCommitted.set(findProfile(user).getSourceVersion() == 1L);
            return invocation.callRealMethod();
        }).when(publisher).publishOne();

        setCurrentTime(NOW);
        profileDispatchService.dispatchDueProfiles();

        assertThat(transactionActive.get()).isFalse();
        assertThat(versionCommitted.get()).isTrue();
        assertThat(findProfile(user).getSourceVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("MQ 작업 준비 시 PENDING 상태와 시작 시각을 저장한다")
    void dispatchDueProfiles_savesAcceptedState() {
        User user = saveChangedPreferenceAt(NOW.minusSeconds(3_601));

        setCurrentTime(NOW);
        profileDispatchService.dispatchDueProfiles();

        RecipientProfile profile = findProfile(user);
        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
        assertThat(profile.getPendingSince())
                .isEqualTo(java.time.LocalDateTime.ofInstant(NOW, ZONE));
        assertThat(profile.getLastChangedAt()).isNull();
        assertThat(profile.getWindowStartedAt()).isNull();
    }

    @Test
    @DisplayName("신규 변경 없이 기한 도래 재시도와 만료 선점을 복구하고 BLOCKED·PUBLISHED는 재발행하지 않는다")
    void recoversExistingJobsWithoutReplayingBlockedOrPublished() {
        User user = saveChangedPreferenceAt(NOW.minusSeconds(3601));
        setCurrentTime(NOW);
        profileDispatchService.dispatchDueProfiles();
        assertPublished(user);
        String requestId = jdbc.queryForObject("SELECT request_id FROM profiling_jobs WHERE profile_id = ?",
                String.class, findProfile(user).getId());
        jdbc.update("""
                UPDATE profiling_jobs SET publish_status = 'RETRY_WAIT', attempt_count = 1,
                    first_failed_at = UTC_TIMESTAMP(6),
                    next_attempt_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE request_id = ?
                """, requestId);
        profileDispatchService.dispatchDueProfiles();
        assertPublished(user);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM profiling_jobs WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(2);

        jdbc.update("""
                UPDATE profiling_jobs SET publish_status = 'CLAIMED', attempt_count = 1,
                    claim_token = ?, claimed_at = TIMESTAMPADD(SECOND, -121, UTC_TIMESTAMP(6)),
                    next_attempt_at = NULL WHERE request_id = ?
                """, UUID.randomUUID().toString(), requestId);
        profileDispatchService.dispatchDueProfiles();
        assertThat(template.receive(ProfilingAmqpConfiguration.REQUEST_QUEUE)).isNull();
        assertThat(jdbc.queryForObject("SELECT publish_status FROM profiling_jobs WHERE request_id = ?",
                String.class, requestId)).isEqualTo("RETRY_WAIT");
        jdbc.update("UPDATE profiling_jobs SET next_attempt_at = UTC_TIMESTAMP(6) WHERE request_id = ?", requestId);
        profileDispatchService.dispatchDueProfiles();
        assertPublished(user);
        profileDispatchService.dispatchDueProfiles();
        assertThat(template.receive(ProfilingAmqpConfiguration.REQUEST_QUEUE)).isNull();
        jdbc.update("UPDATE profiling_jobs SET publish_status = 'BLOCKED' WHERE request_id = ?", requestId);
        profileDispatchService.dispatchDueProfiles();
        assertThat(template.receive(ProfilingAmqpConfiguration.REQUEST_QUEUE)).isNull();
        assertThat(findProfile(user).getSourceVersion()).isEqualTo(1);
    }

    private void assertPublished(User user) {
        var message = template.receive(ProfilingAmqpConfiguration.REQUEST_QUEUE, 5000);
        assertThat(message).isNotNull();
        String requestId = message.getMessageProperties().getMessageId();
        var row = jdbc.queryForMap("SELECT * FROM profiling_jobs WHERE request_id = ?", requestId);
        assertThat(row.get("profile_id")).isEqualTo(findProfile(user).getId());
        assertThat(row.get("publish_status")).isEqualTo("PUBLISHED");
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(row.get("request_body"));
    }

    private User saveChangedPreferenceAt(Instant changedAt) {
        User user = saveUser();
        Category category = saveRootCategory("뷰티");
        setCurrentTime(changedAt);
        preferenceSaveService.saveDislikeCategories(
                user.getId(),
                List.of(category.getId())
        );
        return user;
    }

    private void setCurrentTime(Instant instant) {
        when(clock.instant()).thenReturn(instant);
        when(clock.getZone()).thenReturn(ZONE);
    }

    private RecipientProfile findProfile(User user) {
        return profileRepository.findByRecipient_Id(user.getId())
                .orElseThrow();
    }

    private User saveUser() {
        User user = userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "테스트",
                LocalDate.of(1990, 1, 1)
        ));
        profileRepository.saveAndFlush(new RecipientProfile(user));
        return user;
    }

    private Category saveRootCategory(String prefix) {
        return categoryRepository.saveAndFlush(new Category(
                prefix + "-" + UUID.randomUUID(),
                null
        ));
    }
}
