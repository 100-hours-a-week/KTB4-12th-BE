package com.gift.gift.domain.recommendation.profiling.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.preference.entity.UserGiftPreference;
import com.gift.gift.domain.preference.repository.UserGiftPreferenceRepository;
import com.gift.gift.domain.recommendation.profiling.entity.*;
import com.gift.gift.domain.recommendation.profiling.repository.*;
import com.gift.gift.domain.recommendation.profiling.support.*;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.domain.user.service.UserQueryService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = {
        "app.scheduling.enabled=false", "app.ai-profile.scheduling-enabled=false",
        "app.ai-profile.quiet-period=1h", "app.ai-profile.maximum-window=6h"
})
class ProfilingJobPreparationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    @MockitoBean Clock clock;
    @Autowired ProfilingJobPreparationService service;
    @MockitoSpyBean RecipientProfileRepository profiles;
    @MockitoSpyBean ProfileRequestFactory factory;
    @MockitoSpyBean ProfilingMessageSerializer serializer;
    @Autowired ProfilingJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired UserQueryService userQueries;
    @Autowired UserGiftPreferenceRepository preferences;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private TransactionTemplate tx;
    private Long userId;
    private Long profileId;

    @BeforeEach
    void setUp() {
        // CI 기본 시간대와 무관하게 서비스와 같은 시계를 사용한다.
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            User user = users.saveAndFlush(new User("job-" + UUID.randomUUID() + "@example.com",
                    "$2a$10$" + "a".repeat(53), "작업테스트", LocalDate.of(1990, 1, 1)));
            userId = user.getId();
            RecipientProfile profile = new RecipientProfile(user);
            profile.recordPreferenceChange(LocalDateTime.now(clock).minusDays(1));
            profileId = profiles.saveAndFlush(profile).getId();
        });
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM profiling_jobs WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM user_gift_preferences WHERE user_id = ?", userId);
        profiles.deleteById(profileId);
        users.deleteById(userId);
    }

    @Test
    @DisplayName("작업을 한 번만 준비하고 입력 변경 후에도 저장된 스냅샷을 유지한다")
    void preparesOnceAndKeepsStoredJsonAfterInputChanges() {
        String id = service.prepare(profileId).orElseThrow();
        ProfilingJob first = jobs.findById(id).orElseThrow();
        String body = first.getRequestBody();
        RecipientProfile profile = profiles.findById(profileId).orElseThrow();
        assertThat(profile.getSourceVersion()).isEqualTo(1);
        assertThat(profile.getProfileStatus()).isEqualTo(RecipientProfileStatus.PENDING);
        assertThat(profile.getLastChangedAt()).isNull();
        assertThat(profile.getWindowStartedAt()).isNull();
        assertThat(profile.getPendingSince()).isNotNull();
        assertThat(first.getPublishStatus()).isEqualTo(ProfilingPublishStatus.READY);
        assertThat(first.getResultStatus()).isEqualTo(ProfilingResultStatus.WAITING);
        assertThat(first.getAttemptCount()).isZero();
        assertThat(service.prepare(profileId)).isEmpty();
        changeInput("새로운 취향");
        assertThat(service.prepare(profileId)).isEmpty(); // READY는 먼저 발행한다.
        assertThat(jobs.findById(id).orElseThrow().getRequestBody()).isEqualTo(body);
        assertThat(profiles.findById(profileId).orElseThrow().getLastChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("디바운스 전에는 버전과 변경 대기를 소비하지 않는다")
    void notDueDoesNotConsumeChanges() {
        tx.executeWithoutResult(status -> {
            var profile = profiles.findByIdForUpdate(profileId).orElseThrow();
            profile.clearPendingChange();
            profile.recordPreferenceChange(LocalDateTime.now(clock));
        });
        assertThat(service.prepare(profileId)).isEmpty();
        var profile = profiles.findById(profileId).orElseThrow();
        assertThat(profile.getSourceVersion()).isZero();
        assertThat(profile.getLastChangedAt()).isEqualTo(LocalDateTime.now(clock));
        assertThat(jobCount()).isZero();
    }

    @Test
    @DisplayName("동시 준비 요청은 사용자 잠금 대기 후 최신 상태를 읽어 작업을 하나만 생성한다")
    void concurrentPreparationUsesFreshStateAfterWaitingForUserLock() throws Exception {
        CyclicBarrier beforeUserLock = new CyclicBarrier(2);
        doAnswer(invocation -> {
            beforeUserLock.await(10, TimeUnit.SECONDS);
            return Optional.of(userId);
        }).when(profiles).findRecipientIdByProfileId(profileId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<String>> a = pool.submit(() -> service.prepare(profileId));
            Future<Optional<String>> b = pool.submit(() -> service.prepare(profileId));
            var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            assertThat(profiles.findById(profileId).orElseThrow().getSourceVersion()).isEqualTo(1);
            assertThat(jobCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("직렬화에 실패하면 버전 증가와 입력 변경 초기화를 롤백한다")
    void serializationFailureRollsBackVersionAndChangeClearing() {
        doThrow(new IllegalStateException("직렬화 실패"))
                .when(serializer).serialize(any());
        assertThatThrownBy(() -> service.prepare(profileId)).isInstanceOf(IllegalStateException.class);
        var profile = profiles.findById(profileId).orElseThrow();
        assertThat(profile.getSourceVersion()).isZero();
        assertThat(profile.getLastChangedAt()).isNotNull();
        assertThat(profile.getProfileStatus()).isEqualTo(RecipientProfileStatus.NONE);
        assertThat(jobCount()).isZero();
    }

    @Test
    @DisplayName("작업 저장에 실패하면 차단 작업의 대체 처리와 버전 증가를 롤백한다")
    void failedInsertRollsBackBlockedSupersessionAndNewVersion() {
        String oldId = service.prepare(profileId).orElseThrow();
        jdbc.update("UPDATE profiling_jobs SET publish_status='BLOCKED' WHERE request_id=?", oldId);
        changeInput("재요청");
        // 다음 버전을 미리 넣어 작업 INSERT의 UNIQUE 충돌을 재현한다.
        jdbc.update("INSERT INTO profiling_jobs SELECT ?" +
                ", profile_id, source_version + 1, request_body, publish_status, attempt_count," +
                " first_failed_at, next_attempt_at, claim_token, claimed_at, published_at," +
                " result_status, finished_at, last_error_code, last_error_message, created_at, updated_at" +
                " FROM profiling_jobs WHERE request_id = ?", UUID.randomUUID().toString(), oldId);
        assertThatThrownBy(() -> service.prepare(profileId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var old = jobs.findById(oldId).orElseThrow();
        assertThat(old.getResultStatus()).isEqualTo(ProfilingResultStatus.WAITING);
        assertThat(old.getFinishedAt()).isNull();
        assertThat(profiles.findById(profileId).orElseThrow().getSourceVersion()).isEqualTo(1);
        assertThat(profiles.findById(profileId).orElseThrow().getLastChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("크기 초과 작업은 차단 상태로 저장하고 새 입력으로 대체해도 기존 스냅샷을 보존한다")
    void oversizeIsStoredBlockedAndNewChangeSupersedesWithoutOverwriting() {
        doAnswer(invocation -> new com.gift.gift.domain.recommendation.profiling.dto.request.AiProfileRequest(
                userId, invocation.getArgument(1, Long.class), List.of(), "한".repeat(90_000), List.of()))
                .when(factory).create(any(), anyLong());
        String id = service.prepare(profileId).orElseThrow();
        var blocked = jobs.findById(id).orElseThrow();
        assertThat(blocked.getPublishStatus()).isEqualTo(ProfilingPublishStatus.BLOCKED);
        assertThat(blocked.getLastErrorCode()).isEqualTo("INPUT_TOO_LARGE");
        String body = blocked.getRequestBody();
        reset(factory);
        changeInput("크기를 줄인 입력");
        String nextId = service.prepare(profileId).orElseThrow();
        assertThat(nextId).isNotEqualTo(id);
        var previous = jobs.findById(id).orElseThrow();
        assertThat(previous.getRequestBody()).isEqualTo(body);
        assertThat(previous.getResultStatus()).isEqualTo(ProfilingResultStatus.SUPERSEDED);
        assertThat(previous.getLastErrorCode()).isEqualTo("INPUT_TOO_LARGE");
        assertThat(previous.getFinishedAt()).isNotNull();
        assertThat(jobs.findById(nextId).orElseThrow().getSourceVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("이전 버전이 발행되고 완료되어도 최신 버전의 대기 상태를 유지한다")
    void publishedJobAndIntermediateCallbackKeepLatestPending() {
        String oldId = service.prepare(profileId).orElseThrow();
        jdbc.update("UPDATE profiling_jobs SET publish_status='PUBLISHED' WHERE request_id=?", oldId);
        changeInput("다음 버전");
        service.prepare(profileId).orElseThrow();
        tx.executeWithoutResult(status -> {
            var p = profiles.findByIdForUpdate(profileId).orElseThrow();
            p.markCompleted(1);
        });
        var p = profiles.findById(profileId).orElseThrow();
        assertThat(p.getSourceVersion()).isEqualTo(2);
        assertThat(p.getAnalyzedSourceVersion()).isEqualTo(1);
        assertThat(p.getProfileStatus()).isEqualTo(RecipientProfileStatus.PENDING);
        assertThat(p.getPendingSince()).isNotNull();
        assertThat(jobs.findById(oldId).orElseThrow().getResultStatus())
                .isEqualTo(ProfilingResultStatus.WAITING);
    }

    @Test
    @DisplayName("데이터베이스는 동일 프로필의 버전 중복과 요청 ID 중복을 거부한다")
    void duplicateVersionAndRequestIdAreRejectedByDatabase() {
        String id = service.prepare(profileId).orElseThrow();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO profiling_jobs " +
                "(request_id, profile_id, source_version, request_body, created_at, updated_at) " +
                "VALUES (?, ?, 1, '{}', NOW(6), NOW(6))", UUID.randomUUID().toString(), profileId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO profiling_jobs " +
                "(request_id, profile_id, source_version, request_body, created_at, updated_at) " +
                "VALUES (?, ?, 2, '{}', NOW(6), NOW(6))", id, profileId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    @DisplayName("작업 준비 이후 입력 변경은 현재 스냅샷에 포함하지 않고 다음 디바운스를 위해 유지한다")
    void inputChangeAfterPreparationRemainsForNextDebounce() throws Exception {
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object payload = invocation.callRealMethod();
            snapshotRead.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return payload;
        }).when(factory).create(any(), anyLong());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<String>> prepared = pool.submit(() -> service.prepare(profileId));
            assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> changed = pool.submit(() -> {
                writerStarted.countDown();
                changeInput("작업 준비 이후 변경");
            });
            assertThat(writerStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> changed.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            String id = prepared.get(10, TimeUnit.SECONDS).orElseThrow();
            changed.get(10, TimeUnit.SECONDS);
            var payload = mapper.readTree(jobs.findById(id).orElseThrow().getRequestBody()).path("payload");
            assertThat(payload.path("giftPreference").isNull()).isTrue();
            assertThat(profiles.findById(profileId).orElseThrow().getLastChangedAt()).isNotNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("작업 준비 이전 입력 변경은 잠금 대기 후 스냅샷에 포함한다")
    void inputChangeBeforePreparationIsIncludedAfterLockWait() throws Exception {
        CountDownLatch inputLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch preparationStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            preparationStarted.countDown();
            return Optional.of(userId);
        }).when(profiles).findRecipientIdByProfileId(profileId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> changed = pool.submit(() -> tx.executeWithoutResult(status -> {
                User user = userQueries.findActiveUserForUpdate(userId).orElseThrow();
                var p = profiles.findByIdForUpdate(profileId).orElseThrow();
                preferences.saveAndFlush(new UserGiftPreference(user, "먼저 변경된 입력"));
                p.recordPreferenceChange(LocalDateTime.now(clock).minusHours(1));
                inputLocked.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(inputLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Optional<String>> prepared = pool.submit(() -> service.prepare(profileId));
            assertThat(preparationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> prepared.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            changed.get(10, TimeUnit.SECONDS);
            String id = prepared.get(10, TimeUnit.SECONDS).orElseThrow();
            assertThat(mapper.readTree(jobs.findById(id).orElseThrow().getRequestBody())
                    .path("payload").path("giftPreference").asText()).isEqualTo("먼저 변경된 입력");
            assertThat(profiles.findById(profileId).orElseThrow().getLastChangedAt()).isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void changeInput(String text) {
        tx.executeWithoutResult(status -> {
            User user = userQueries.findActiveUserForUpdate(userId).orElseThrow();
            var p = profiles.findByIdForUpdate(profileId).orElseThrow();
            preferences.save(new UserGiftPreference(user, text));
            p.recordPreferenceChange(LocalDateTime.now(clock).minusHours(1));
        });
    }

    private long jobCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM profiling_jobs WHERE profile_id=?", Long.class, profileId);
    }
}
