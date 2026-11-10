package com.gift.gift.domain.recommendation.profiling.service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.product.cache.AiRecommendedProductCache;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Data;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Outcome;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Payload;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Error;
import com.gift.gift.domain.recommendation.profiling.entity.ProfilingResultStatus;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.event.RecommendationAnalysisCompletedEvent;
import com.gift.gift.domain.recommendation.profiling.repository.*;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingPublishStore.Claim;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService.Decision;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService.Reason;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService.Result;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingResultFingerprint;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.domain.user.service.UserQueryService;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
        "app.ai-profile.amqp.enabled=false", "app.ai-profile.scheduling-enabled=false",
        "app.ai-profile.cleanup-enabled=false", "app.scheduling.enabled=false"
})
class ProfilingResultServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-11-09T00:00:00.123456Z");
    private static final LocalDateTime CHANGED = LocalDateTime.of(2030, 11, 8, 8, 0);

    @Autowired private ProfilingResultService service;
    @Autowired private ProfilingPublishStore publisher;
    @Autowired private ProfilingRecoveryService recovery;
    @Autowired private ProfilingJobCleanupStore cleanup;
    @Autowired private ProfilingResultFingerprint fingerprint;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository products;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ProfilingJobRepository jobs;
    @MockitoSpyBean private RecipientProfileRepository profiles;
    @MockitoSpyBean private RecipientRecommendedProductRepository recommendations;
    @MockitoSpyBean private UserQueryService users;
    @MockitoSpyBean private AiRecommendedProductCache cache;
    @MockitoSpyBean(name = "productRedisTemplate") private StringRedisTemplate redis;
    @MockitoBean private Clock clock;

    private TransactionTemplate tx;
    private Long recipientId;
    private Long profileId;
    private Long rootCategoryId;
    private Long childCategoryId;
    private List<Long> productIds;
    private final Map<Long, String> requestIds = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            User user = userRepository.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                    "$2a$10$" + "a".repeat(53), "결과 저장 테스트", LocalDate.of(2000, 1, 1)));
            recipientId = user.getId();
            profileId = profiles.saveAndFlush(new RecipientProfile(user)).getId();
            Category root = new Category("root-" + UUID.randomUUID(), null);
            entityManager.persist(root);
            Category child = new Category("child-" + UUID.randomUUID(), root);
            entityManager.persist(child);
            productIds = List.of(product(child, "A"), product(child, "B"), product(child, "C"));
            rootCategoryId = root.getId();
            childCategoryId = child.getId();
        });
        jdbc.update("""
                UPDATE recipient_profiles SET source_version=8, analyzed_source_version=6,
                    profile_status='PENDING', pending_since=?, last_changed_at=?, window_started_at=? WHERE id=?
                """, CHANGED, CHANGED, CHANGED, profileId);
        jdbc.update("""
                INSERT INTO recipient_recommended_products
                (recipient_id,product_id,rank_order,source_version,created_at,updated_at)
                VALUES (?,?,1,6,NOW(6),NOW(6))
                """, recipientId, productIds.getFirst());
        insertJob(7);
        insertJob(8);
        clearInvocations(jobs, profiles, recommendations, users, cache, redis);
    }

    @AfterEach
    void tearDown() {
        reset(jobs, profiles, recommendations, users, cache, redis);
        if (recipientId == null) {
            return;
        }
        jdbc.update("DELETE FROM profiling_jobs WHERE profile_id=?", profileId);
        jdbc.update("DELETE FROM recipient_recommended_products WHERE recipient_id=?", recipientId);
        profiles.deleteById(profileId);
        products.deleteAllByIdInBatch(productIds);
        jdbc.update("DELETE FROM categories WHERE id=?", childCategoryId);
        jdbc.update("DELETE FROM categories WHERE id=?", rootCategoryId);
        userRepository.deleteById(recipientId);
        redis.delete("gift:product:ai-recommended-version:" + recipientId);
    }

    @Test
    @DisplayName("최신 성공은 RC 트랜잭션에서 사용자·프로필·작업 순서로 잠그고 원자적으로 커밋한다")
    void latestSuccessLocksInOrderAndCommitsAllState(CapturedOutput output) {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return callDefault(invocation);
        }).when(recommendations).saveAllAndFlush(anyList());

        var message = success(8, reversed());
        Result result = service.save(message);

        var order = inOrder(jobs, users, profiles);
        order.verify(jobs).findResultTarget(message.requestId());
        order.verify(users).findActiveUserForUpdate(recipientId);
        order.verify(profiles).findByIdForUpdate(profileId);
        order.verify(jobs).findByRequestIdForUpdate(message.requestId());
        assertThat(result.decision()).isEqualTo(Decision.RECORDED);
        assertThat(result.recommendationsChanged()).isTrue();
        assertThat(result.publishedAtInferred()).isTrue();
        assertThat(recommendedIds()).containsExactlyElementsOf(reversed());
        assertThat(profile()).containsEntry("profile_status", "COMPLETED")
                .containsEntry("analyzed_source_version", 8L).containsEntry("pending_since", null)
                .containsEntry("last_changed_at", CHANGED);
        assertThat(job(8)).containsEntry("result_status", "COMPLETED")
                .containsEntry("result_fingerprint", fingerprint.hash(message))
                .containsEntry("publish_status", "PUBLISHED");
        assertThat(jobTime(8, "finished_at")).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneId.of("Asia/Seoul")));
        assertThat(jobTime(8, "published_at")).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(output).contains("PROFILING_RESULT_DECISION", "publishedAtInferred=true");
        verify(cache).onRecommendationAnalysisCompleted(any());
    }

    @Test
    @DisplayName("결과 선도착은 선점·예약을 해제하고 기존 발행 시각과 발행 시도 이력은 보존한다")
    void preservesPublishedAtAndAttemptHistoryWhileClearingClaim() {
        LocalDateTime published = LocalDateTime.of(2026, 11, 1, 0, 0);
        jdbc.update("""
                UPDATE profiling_jobs SET publish_status='CLAIMED',claim_token=?,claimed_at=UTC_TIMESTAMP(6),
                    next_attempt_at=UTC_TIMESTAMP(6),published_at=?,attempt_count=2,
                    first_failed_at=UTC_TIMESTAMP(6),last_error_code='PUBLISH_NACK' WHERE request_id=?
                """, UUID.randomUUID().toString(), published, requestIds.get(8L));
        var firstFailure = jobTime(8, "first_failed_at");
        assertThat(service.save(success(8, reversed())).publishedAtInferred()).isFalse();
        assertThat(jobTime(8, "published_at")).isEqualTo(published);
        assertThat(jobTime(8, "first_failed_at")).isEqualTo(firstFailure);
        assertThat(job(8)).containsEntry("claim_token", null).containsEntry("claimed_at", null)
                .containsEntry("next_attempt_at", null).containsEntry("attempt_count", 2)
                .containsEntry("last_error_code", null);
    }

    @Test
    @DisplayName("시각만 달라진 동일 성공 재수신은 추천·해시·종료/발행 시각과 캐시를 재반영하지 않는다")
    void duplicateIsNoOpEvenWithDifferentCreatedAt() {
        var message = success(8, reversed());
        service.save(message);
        var before = snapshot();
        clearInvocations(recommendations, cache);
        var resent = new ProfilingResultMessage(1, message.requestId(), "2031-01-01T00:00:00Z", message.payload());
        assertThat(service.save(resent).decision()).isEqualTo(Decision.DUPLICATE);
        assertThat(snapshot()).isEqualTo(before);
        verify(recommendations, never()).saveAllAndFlush(anyList());
        verify(cache, never()).onRecommendationAnalysisCompleted(any());
    }

    @Test
    @DisplayName("같은 요청의 추천 순서 변경·성공/실패 변경은 기존 결과를 덮지 않고 격리한다")
    void conflictingResultsAreQuarantined() {
        service.save(success(8, reversed()));
        var before = snapshot();
        assertThat(service.save(success(8, List.of(productIds.get(1), productIds.get(2)))).reason())
                .isEqualTo(Reason.CONFLICTING_RESULT);
        assertThat(service.save(failure(8, "MODEL_FAILED", "실패")).reason())
                .isEqualTo(Reason.CONFLICTING_RESULT);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("최신 실패는 기존 추천·분석 버전·새 변경을 보존하고 실패 설명만 바뀐 재수신은 중복이다")
    void latestFailurePreservesRecommendationsAndDebounce() {
        var beforeRecommendations = recommendationRows();
        assertThat(service.save(failure(8, "MODEL_FAILED", "첫 설명")).decision()).isEqualTo(Decision.RECORDED);
        assertThat(profile()).containsEntry("profile_status", "FAILED")
                .containsEntry("analyzed_source_version", 6L).containsEntry("pending_since", null)
                .containsEntry("last_changed_at", CHANGED)
                .containsEntry("window_started_at", CHANGED);
        assertThat(recommendationRows()).isEqualTo(beforeRecommendations);
        assertThat(job(8)).containsEntry("result_status", "FAILED").containsEntry("last_error_code", "AI_RESULT_FAILED");
        var before = snapshot();
        assertThat(service.save(failure(8, "MODEL_FAILED", "다른 설명")).decision()).isEqualTo(Decision.DUPLICATE);
        assertThat(service.save(failure(8, "OTHER_FAILURE", "설명")).reason()).isEqualTo(Reason.CONFLICTING_RESULT);
        assertThat(snapshot()).isEqualTo(before);
        verify(cache, never()).onRecommendationAnalysisCompleted(any());
    }

    @Test
    @DisplayName("중간 성공 v7은 저장 v6을 갱신하지만 최신 v8 대기와 새 변경을 유지한다")
    void intermediateSuccessKeepsLatestPending() {
        service.save(success(7, reversed()));
        assertThat(profile()).containsEntry("analyzed_source_version", 7L).containsEntry("source_version", 8L)
                .containsEntry("profile_status", "PENDING")
                .containsEntry("pending_since", CHANGED)
                .containsEntry("last_changed_at", CHANGED);
        assertThat(job(7)).containsEntry("result_status", "COMPLETED");
        assertThat(job(8)).containsEntry("result_status", "WAITING");
        assertThat(recommendedIds()).containsExactlyElementsOf(reversed());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("v8 반영 후 도착한 WAITING v7의 성공/실패는 해당 작업만 종료한다")
    void olderFinalResultOnlyTerminatesItsJob(boolean succeeded) {
        service.save(success(8, reversed()));
        var beforeProfile = profile();
        var beforeRecommendations = recommendationRows();
        var message = succeeded ? success(7, List.of(productIds.getFirst())) : failure(7, "FAIL", "실패");
        assertThat(service.save(message).recommendationsChanged()).isFalse();
        assertThat(profile()).isEqualTo(beforeProfile);
        assertThat(recommendationRows()).isEqualTo(beforeRecommendations);
        assertThat(job(7)).containsEntry("result_status", succeeded ? "COMPLETED" : "FAILED")
                .containsEntry("result_fingerprint", fingerprint.hash(message));
    }

    @Test
    @DisplayName("이전 요청의 실패는 최신 대기 프로필에 영향을 주지 않는다")
    void intermediateFailureKeepsLatestProfileUntouched() {
        var beforeProfile = profile();
        var beforeRecommendations = recommendationRows();
        service.save(failure(7, "FAIL", "실패"));
        assertThat(profile()).isEqualTo(beforeProfile);
        assertThat(recommendationRows()).isEqualTo(beforeRecommendations);
        assertThat(job(7)).containsEntry("result_status", "FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED"})
    @DisplayName("이미 종료됐지만 해시가 없는 기존 작업은 보충하지 않고 격리한다")
    void terminalWithoutFingerprintIsNotBackfilled(String state) {
        jdbc.update("UPDATE profiling_jobs SET result_status=?,finished_at=NOW(6) WHERE request_id=?",
                state, requestIds.get(8L));
        var before = snapshot();
        assertThat(service.save(success(8, reversed())).reason()).isEqualTo(Reason.TERMINAL_WITHOUT_FINGERPRINT);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("유효 SUPERSEDED는 관측 후 무시하고 잘못된 수신자는 동일 상태여도 격리한다")
    void supersededStillValidatesIdentityWithoutMutatingState() {
        jdbc.update("""
                UPDATE profiling_jobs SET result_status='SUPERSEDED',publish_status='BLOCKED',finished_at=NOW(6)
                WHERE request_id=?
                """, requestIds.get(8L));
        var before = snapshot();
        assertThat(service.save(success(8, reversed())).decision()).isEqualTo(Decision.IGNORED);
        var mismatch = new ProfilingResultMessage(1, requestIds.get(8L), NOW.toString(),
                new Payload(recipientId + 1, 8, Outcome.SUCCEEDED, new Data(reversed()), null));
        assertThat(service.save(mismatch).reason()).isEqualTo(Reason.IDENTITY_MISMATCH);
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recipient", "version", "future", "unknown", "inactive", "hash", "finished", "profile"})
    @DisplayName("작업 대응·버전·상태 불일치는 정상 작업을 변경하지 않고 격리한다")
    void quarantinesInvalidIdentityOrState(String scenario) {
        var message = success(8, reversed());
        Reason expected;
        switch (scenario) {
            case "recipient" -> {
                message = new ProfilingResultMessage(1, requestIds.get(8L), NOW.toString(),
                        new Payload(recipientId + 1, 8, Outcome.SUCCEEDED, new Data(reversed()), null));
                expected = Reason.IDENTITY_MISMATCH;
            }
            case "version" -> {
                message = new ProfilingResultMessage(1, requestIds.get(8L), NOW.toString(),
                        new Payload(recipientId, 7, Outcome.SUCCEEDED, new Data(reversed()), null));
                expected = Reason.IDENTITY_MISMATCH;
            }
            case "future" -> {
                insertJob(9);
                message = success(9, reversed());
                expected = Reason.INVALID_VERSION;
            }
            case "unknown" -> {
                message = new ProfilingResultMessage(1, UUID.randomUUID().toString(), NOW.toString(), message.payload());
                expected = Reason.JOB_NOT_FOUND;
            }
            case "inactive" -> {
                jdbc.update("UPDATE users SET status='DELETED' WHERE id=?", recipientId);
                expected = Reason.RECIPIENT_NOT_ACTIVE;
            }
            case "hash" -> {
                jdbc.update("UPDATE profiling_jobs SET result_fingerprint=? WHERE request_id=?",
                        "b".repeat(64), requestIds.get(8L));
                expected = Reason.INCONSISTENT_JOB_STATE;
            }
            case "finished" -> {
                jdbc.update("UPDATE profiling_jobs SET finished_at=NOW(6) WHERE request_id=?", requestIds.get(8L));
                expected = Reason.INCONSISTENT_JOB_STATE;
            }
            case "profile" -> {
                jdbc.update("UPDATE recipient_profiles SET profile_status='NONE' WHERE id=?", profileId);
                message = failure(8, "FAIL", "실패");
                expected = Reason.INCONSISTENT_PROFILE_STATE;
            }
            default -> throw new AssertionError(scenario);
        }
        var before = snapshot();
        var result = service.save(message);
        assertThat(result.decision()).isEqualTo(Decision.QUARANTINE);
        assertThat(result.reason()).isEqualTo(expected);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("없는·삭제된 상품을 제외한 순위를 유지하고 해시는 제외 전 원본 목록으로 저장한다")
    void filtersProductsWithoutChangingFingerprintInput() {
        jdbc.update("UPDATE products SET deleted_at=NOW(6) WHERE id=?", productIds.get(1));
        long missing = jdbc.queryForObject("SELECT MAX(id)+1000 FROM products", Long.class);
        var message = success(8, List.of(productIds.get(2), productIds.get(1), missing, productIds.getFirst()));
        service.save(message);
        assertThat(recommendedIds()).containsExactly(productIds.get(2), productIds.getFirst());
        assertThat(jdbc.queryForList("""
                SELECT rank_order FROM recipient_recommended_products WHERE recipient_id=? ORDER BY rank_order
                """, Integer.class, recipientId)).containsExactly(1, 2);
        assertThat(job(8)).containsEntry("result_fingerprint", fingerprint.hash(message));
        assertThat(service.save(message).decision()).isEqualTo(Decision.DUPLICATE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("빈 추천 또는 전부 제외된 추천은 기존 추천을 비우고 정상 완료한다")
    void emptySuccessClearsRecommendations(boolean allDropped) {
        List<Long> ids = List.of();
        if (allDropped) {
            jdbc.update("UPDATE products SET deleted_at=NOW(6) WHERE id=?", productIds.getFirst());
            ids = List.of(productIds.getFirst());
        }
        service.save(success(8, ids));
        assertThat(recommendedIds()).isEmpty();
        assertThat(profile()).containsEntry("profile_status", "COMPLETED");
        assertThat(job(8)).containsEntry("result_status", "COMPLETED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"recommendations", "job", "commit"})
    @DisplayName("추천 저장·작업 flush·커밋 전 실패는 추천·프로필·작업·해시를 모두 롤백한다")
    void rollsBackAllEffectsAndDoesNotPublishCommittedEvent(String stage, CapturedOutput output) {
        var before = snapshot();
        if (stage.equals("recommendations")) {
            doThrow(new DataAccessResourceFailureException("test-write-failure"))
                    .when(recommendations).saveAllAndFlush(anyList());
        } else {
            doAnswer(invocation -> {
                Object answer = callDefault(invocation);
                if (stage.equals("job")) {
                    throw new DataAccessResourceFailureException("test-after-flush-failure");
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new DataAccessResourceFailureException("test-before-commit-failure");
                    }
                });
                return answer;
            }).when(jobs).flush();
        }
        assertThatThrownBy(() -> service.save(success(8, reversed()))).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(snapshot()).isEqualTo(before);
        verify(cache, never()).onRecommendationAnalysisCompleted(any());
        assertThat(output).doesNotContain("PROFILING_RESULT_DECISION");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("실제 트랜잭션의 커밋 후 오류만 커밋 관측자에 전달하고 롤백은 전달하지 않는다")
    void commitObserverReflectsActualTransactionCompletion(boolean afterCommit) {
        var committed = new java.util.concurrent.atomic.AtomicReference<Result>();
        var before = snapshot();
        doAnswer(invocation -> {
            Object answer = callDefault(invocation);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    if (!afterCommit) throw new IllegalStateException("before commit");
                }

                @Override
                public void afterCommit() {
                    if (afterCommit) throw new IllegalStateException("after commit");
                }
            });
            return answer;
        }).when(jobs).flush();
        assertThatThrownBy(() -> service.saveWithCommitObserver(success(8, reversed()), committed::set))
                .isInstanceOf(IllegalStateException.class);
        if (afterCommit) {
            assertThat(committed.get()).isNotNull();
            assertThat(committed.get().decision()).isEqualTo(Decision.RECORDED);
            assertThat(job(8)).containsEntry("result_status", "COMPLETED");
        } else {
            assertThat(committed.get()).isNull();
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("커밋 후 Redis 장애는 DB 커밋을 실패로 바꾸거나 동일 결과를 다시 저장하지 않는다")
    void cacheFailureDoesNotUndoCommittedResult() {
        doThrow(new DataAccessResourceFailureException("test-redis-down"))
                .when(redis).delete("gift:product:ai-recommended-version:" + recipientId);
        assertThat(service.save(success(8, reversed())).decision()).isEqualTo(Decision.RECORDED);
        var before = snapshot();
        assertThat(service.save(success(8, reversed())).decision()).isEqualTo(Decision.DUPLICATE);
        assertThat(snapshot()).isEqualTo(before);
        verify(recommendations, times(1)).saveAllAndFlush(anyList());
    }

    @Test
    @DisplayName("v7의 커밋 후 캐시 처리가 v8보다 늦게 끝나도 최신 추천 버전을 조회한다")
    void outOfOrderCommittedEventsDoNotRegressCachedVersion() throws Exception {
        CountDownLatch olderCommitted = new CountDownLatch(1);
        CountDownLatch releaseOlderEvent = new CountDownLatch(1);
        doAnswer(invocation -> {
            RecommendationAnalysisCompletedEvent event = invocation.getArgument(0);
            if (event.analyzedSourceVersion() == 7) {
                olderCommitted.countDown();
                if (!releaseOlderEvent.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("이전 캐시 이벤트 대기 시간 초과");
                }
            }
            return callDefault(invocation);
        }).when(cache).onRecommendationAnalysisCompleted(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var older = executor.submit(() -> service.save(success(7, List.of(productIds.getFirst()))));
            try {
                assertThat(olderCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(service.save(success(8, reversed())).decision()).isEqualTo(Decision.RECORDED);
            } finally {
                releaseOlderEvent.countDown();
            }
            older.get(10, TimeUnit.SECONDS);
            assertThat(cache.resolveAnalyzedSourceVersion(recipientId,
                    () -> jdbc.queryForObject("SELECT analyzed_source_version FROM recipient_profiles WHERE id=?",
                            Long.class, profileId)).version()).isEqualTo(8);
            assertThat(recommendedIds()).containsExactlyElementsOf(reversed());
        } finally {
            releaseOlderEvent.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("동시 최초 수신은 한 번만 저장하고 후행 동일/상충 결과를 잠금 후 판정한다")
    void serializesConcurrentFirstResults(boolean conflicting) throws Exception {
        var first = success(8, reversed());
        var second = success(8, conflicting ? List.of(productIds.getFirst()) : reversed());
        var results = overlapResultSaves(first, second);
        assertThat(results.getFirst().decision()).isEqualTo(Decision.RECORDED);
        assertThat(results.get(1).decision()).isEqualTo(conflicting ? Decision.QUARANTINE : Decision.DUPLICATE);
        assertThat(recommendedIds()).containsExactlyElementsOf(reversed());
        verify(recommendations, times(1)).saveAllAndFlush(anyList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("v7/v8 동시 결과는 먼저 잠근 버전과 무관하게 최신 추천을 유지한다")
    void concurrentVersionsKeepLatest(boolean latestFirst) throws Exception {
        var older = success(7, List.of(productIds.getFirst()));
        var latest = success(8, reversed());
        overlapResultSaves(latestFirst ? latest : older, latestFirst ? older : latest);
        assertThat(profile()).containsEntry("profile_status", "COMPLETED").containsEntry("analyzed_source_version", 8L);
        assertThat(recommendedIds()).containsExactlyElementsOf(reversed());
        assertThat(job(7)).containsEntry("result_status", "COMPLETED");
        assertThat(job(8)).containsEntry("result_status", "COMPLETED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"published", "failed", "release", "expired"})
    @DisplayName("결과가 먼저 종료된 뒤 Publisher의 성공·실패·반납·만료 회수는 결과를 덮지 못한다")
    void resultWinsPublisherRace(String action) throws Exception {
        Claim claim = claimLatest();
        if (action.equals("expired")) {
            jdbc.update("UPDATE profiling_jobs SET claimed_at=UTC_TIMESTAMP(6)-INTERVAL 3 MINUTE WHERE request_id=?",
                    requestIds.get(8L));
        }
        CountDownLatch flushed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockResultFlush(flushed, release);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var result = executor.submit(() -> service.save(success(8, reversed())));
            assertThat(flushed.await(10, TimeUnit.SECONDS)).isTrue();
            var late = executor.submit(() -> applyPublisher(action, claim));
            try {
                if (!action.equals("expired")) {
                    assertThrows(TimeoutException.class, () -> late.get(200, TimeUnit.MILLISECONDS));
                }
            } finally {
                release.countDown();
            }
            assertThat(result.get(10, TimeUnit.SECONDS).decision()).isEqualTo(Decision.RECORDED);
            assertThat(late.get(10, TimeUnit.SECONDS)).isFalse();
            var before = snapshot();
            assertThat(applyPublisher(action, claim)).isFalse();
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"published", "failed", "release", "expired"})
    @DisplayName("Publisher의 확인·실패·반납·만료 회수가 먼저 끝나도 유효 결과는 정상 종료한다")
    void resultAfterPublisherStateChange(String action) {
        Claim claim = claimLatest();
        if (action.equals("expired")) {
            jdbc.update("UPDATE profiling_jobs SET claimed_at=UTC_TIMESTAMP(6)-INTERVAL 3 MINUTE WHERE request_id=?",
                    requestIds.get(8L));
        }
        applyPublisher(action, claim);
        LocalDateTime existingPublishedAt = jobTime(8, "published_at");
        var result = service.save(success(8, reversed()));
        assertThat(result.decision()).isEqualTo(Decision.RECORDED);
        assertThat(result.publishedAtInferred()).isEqualTo(existingPublishedAt == null);
        if (existingPublishedAt != null) {
            assertThat(jobTime(8, "published_at")).isEqualTo(existingPublishedAt);
        }
        assertThat(job(8)).containsEntry("publish_status", "PUBLISHED").containsEntry("result_status", "COMPLETED")
                .containsEntry("claim_token", null).containsEntry("next_attempt_at", null);
    }

    @Test
    @DisplayName("결과가 사용자 잠금을 먼저 잡으면 수동 복구는 커밋 후 종료 상태를 확인하고 거부된다")
    void resultWinsManualRecoveryRace() throws Exception {
        jdbc.update("UPDATE profiling_jobs SET publish_status='BLOCKED' WHERE request_id=?", requestIds.get(8L));
        var command = recoveryCommand(ProfilingRecoveryService.Action.RESUME_SAME);
        CountDownLatch flushed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockResultFlush(flushed, release);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var result = executor.submit(() -> service.save(success(8, reversed())));
            assertThat(flushed.await(10, TimeUnit.SECONDS)).isTrue();
            var manual = executor.submit(() -> recovery.recover(command));
            try {
                assertThrows(TimeoutException.class, () -> manual.get(200, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertThat(result.get(10, TimeUnit.SECONDS).decision()).isEqualTo(Decision.RECORDED);
            assertThatThrownBy(() -> manual.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(job(8)).containsEntry("result_status", "COMPLETED");
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @EnumSource(ProfilingRecoveryService.Action.class)
    @DisplayName("수동 복구가 먼저 끝난 경우 동일 입력 결과는 수락하고 대체된 요청 결과는 무시한다")
    void handlesResultAfterManualRecovery(ProfilingRecoveryService.Action action) {
        jdbc.update("UPDATE profiling_jobs SET publish_status='BLOCKED' WHERE request_id=?", requestIds.get(8L));
        recovery.recover(recoveryCommand(action));
        var beforeProfile = profile();
        var result = service.save(success(8, reversed()));
        if (action == ProfilingRecoveryService.Action.RESUME_SAME) {
            assertThat(result.decision()).isEqualTo(Decision.RECORDED);
        } else {
            assertThat(result.decision()).isEqualTo(Decision.IGNORED);
            assertThat(profile()).isEqualTo(beforeProfile);
            assertThat(profile()).containsEntry("source_version", 9L).containsEntry("profile_status", "PENDING");
            assertThat(job(8)).containsEntry("result_status", "SUPERSEDED").containsEntry("result_fingerprint", null);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("완료/실패 이력 정리 후 늦은 결과는 추천을 변경하지 않고 작업 부재로 격리한다")
    void lateResultAfterCleanupIsQuarantined(boolean failed) {
        var message = failed ? failure(8, "FAIL", "실패") : success(8, reversed());
        service.save(message);
        var beforeProfile = profile();
        var beforeRecommendations = recommendationRows();
        assertThat(cleanup.deleteBatch(failed ? ProfilingResultStatus.FAILED : ProfilingResultStatus.COMPLETED,
                LocalDateTime.ofInstant(NOW, ZoneId.of("Asia/Seoul")).plusDays(1), 100)).isEqualTo(1);
        assertThat(service.save(message).reason()).isEqualTo(Reason.JOB_NOT_FOUND);
        assertThat(profile()).isEqualTo(beforeProfile);
        assertThat(recommendationRows()).isEqualTo(beforeRecommendations);
    }

    private List<Result> overlapResultSaves(ProfilingResultMessage first, ProfilingResultMessage second) throws Exception {
        CountDownLatch flushed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockResultFlush(flushed, release);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> service.save(first));
            assertThat(flushed.await(10, TimeUnit.SECONDS)).isTrue();
            var b = executor.submit(() -> service.save(second));
            try {
                assertThrows(TimeoutException.class, () -> b.get(200, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    private void blockResultFlush(CountDownLatch flushed, CountDownLatch release) {
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object answer = callDefault(invocation);
            if (first.compareAndSet(true, false)) {
                flushed.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("결과 커밋 대기 시간 초과");
                }
            }
            return answer;
        }).when(jobs).flush();
    }

    private Claim claimLatest() {
        jdbc.update("UPDATE profiling_jobs SET publish_status='READY' WHERE request_id=?", requestIds.get(8L));
        Claim claim = publisher.claimOne().orElseThrow();
        assertThat(claim.requestId()).isEqualTo(requestIds.get(8L));
        return claim;
    }

    private boolean applyPublisher(String action, Claim claim) {
        return switch (action) {
            case "published" -> publisher.markPublished(claim);
            case "failed" -> publisher.markFailed(claim, false, "PUBLISH_NACK");
            case "release" -> publisher.releaseUnsent(claim);
            case "expired" -> publisher.claimOne().isPresent();
            default -> throw new AssertionError(action);
        };
    }

    private ProfilingRecoveryService.Command recoveryCommand(ProfilingRecoveryService.Action action) {
        return new ProfilingRecoveryService.Command(action, profileId, requestIds.get(8L), 8,
                jobTime(8, "updated_at"), "test-operator", "결과 경합 시험");
    }

    private Object callDefault(InvocationOnMock invocation) throws Throwable {
        return mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }

    private Long product(Category category, String name) {
        return products.saveAndFlush(new Product(category, name, "브랜드", null, BigDecimal.valueOf(1000), 10)).getId();
    }

    private void insertJob(long version) {
        String requestId = UUID.randomUUID().toString();
        requestIds.put(version, requestId);
        jdbc.update("""
                INSERT INTO profiling_jobs (request_id,profile_id,source_version,request_body,publish_status,
                    published_at,created_at,updated_at)
                VALUES (?,?,?,'{"snapshot":"fixed-input"}','PUBLISHED',UTC_TIMESTAMP(6),NOW(6),NOW(6))
                """, requestId, profileId, version);
        // 최신 요청은 발행 확인보다 결과가 먼저 오는 기본 fixture다.
        if (version == 8) {
            jdbc.update("UPDATE profiling_jobs SET publish_status='READY',published_at=NULL WHERE request_id=?", requestId);
        }
    }

    private ProfilingResultMessage success(long version, List<Long> ids) {
        return new ProfilingResultMessage(1, requestIds.get(version), "2026-11-09T00:00:00Z",
                new Payload(recipientId, version, Outcome.SUCCEEDED, new Data(ids), null));
    }

    private ProfilingResultMessage failure(long version, String code, String message) {
        return new ProfilingResultMessage(1, requestIds.get(version), "2026-11-09T00:00:00Z",
                new Payload(recipientId, version, Outcome.FAILED, null, new Error(code, message)));
    }

    private List<Long> reversed() {
        return List.of(productIds.get(2), productIds.get(1));
    }

    private Map<String, Object> profile() {
        return jdbc.queryForMap("SELECT * FROM recipient_profiles WHERE id=?", profileId);
    }

    private Map<String, Object> job(long version) {
        return jdbc.queryForMap("SELECT * FROM profiling_jobs WHERE request_id=?", requestIds.get(version));
    }

    private LocalDateTime jobTime(long version, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM profiling_jobs WHERE request_id=?",
                LocalDateTime.class, requestIds.get(version));
    }

    private List<Map<String, Object>> recommendationRows() {
        return jdbc.queryForList("SELECT * FROM recipient_recommended_products WHERE recipient_id=? ORDER BY rank_order",
                recipientId);
    }

    private List<Long> recommendedIds() {
        return jdbc.queryForList("""
                SELECT product_id FROM recipient_recommended_products WHERE recipient_id=? ORDER BY rank_order
                """, Long.class, recipientId);
    }

    private Map<String, Object> snapshot() {
        return Map.of("profile", profile(), "recommendations", recommendationRows(), "jobs", jdbc.queryForList(
                "SELECT * FROM profiling_jobs WHERE profile_id=? ORDER BY source_version", profileId));
    }
}
