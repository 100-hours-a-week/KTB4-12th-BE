package com.gift.gift.domain.notification.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.gift.event.GiftCreatedEvent;
import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.repository.NotificationCommonOutboxRepository;
import com.gift.gift.domain.notification.repository.NotificationOutboxRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"app.notification.common-outbox.enabled=true", "app.scheduling.enabled=false",
    "app.notification.outbox.batch-size=20"})
class NotificationCommonOutboxIntegrationTest {
    @Autowired private NotificationOutboxWriter writer;
    @Autowired private NotificationCommonOutboxDispatcher dispatcher;
    @Autowired private NotificationCommonOutboxTransactionService transactions;
    @Autowired private NotificationOutboxDispatcher legacyDispatcher;
    @Autowired private NotificationOutboxRepository legacyRepository;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @MockitoSpyBean private NotificationCommonOutboxRepository repository;
    @MockitoSpyBean private NotificationCreateTransactionService creation;
    @MockitoBean private StringRedisTemplate redis;
    private NotificationCreateTransactionService creationTarget;
    private Long recipient;
    private NotificationCreateCommand command;

    @BeforeEach
    void setup() {
        creationTarget = AopTestUtils.getUltimateTargetObject(creation);
        recipient = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "$2a$10$" + "a".repeat(53),
            "전환 전", LocalDate.of(1990, 1, 1))).getId();
        command = new NotificationCreateCommand(recipient, "fixture", NotificationType.GIFT_RECEIVED,
            null, null, "알림", "내용", "common:" + UUID.randomUUID());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM outbox_event WHERE (aggregate_type='USER' AND aggregate_id=?) OR deduplication_key LIKE ?",
            recipient.toString(), command.deduplicationKey() + "%");
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_id=?", recipient);
        jdbc.update("DELETE FROM notifications WHERE recipient_id=?", recipient);
        jdbc.update("DELETE FROM users WHERE id=?", recipient);
    }

    private void enqueue() {
        new TransactionTemplate(manager).executeWithoutResult(tx -> writer.enqueue(command));
    }

    private Long id() {
        return jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_type=? AND deduplication_key=?",
            Long.class, NotificationCommonOutboxRepository.EVENT_TYPE, command.deduplicationKey());
    }

    private String state() {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?", String.class, id());
    }

    private long commonCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_type='USER' AND aggregate_id=?",
            Long.class, recipient.toString());
    }

    private long notificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id=?", Long.class, recipient);
    }

    @Test
    @DisplayName("공통 기록은 최초 payload를 유지하고 Redis 장애·늦은 실패에도 완료와 단일 알림을 보존한다")
    void commonWriterIsIdempotentAndAtomic() {
        enqueue();
        var firstId = jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE id=?", String.class, id());
        new TransactionTemplate(manager).executeWithoutResult(tx -> writer.enqueue(new NotificationCreateCommand(
            recipient, command.sourceIdentifier(), command.type(), null, null, "바뀐 제목", "바뀐 내용", command.deduplicationKey())));
        assertThat(commonCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id=?", Long.class, recipient)).isZero();
        assertThat(jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE id=?", String.class, id())).isEqualTo(firstId);
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE id=?", String.class, id());
        assertThat(mapper.readTree(payload).path("title").asText()).isEqualTo("알림");
        assertThat(notificationCount()).isZero();
        when(redis.convertAndSend(anyString(), anyString())).thenThrow(new RedisConnectionFailureException("down"));
        dispatcher.dispatch();
        dispatcher.dispatch();
        transactions.recordFailure(id(), false, "LateFailure");
        assertThat(state()).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("SELECT failure_count FROM outbox_event WHERE id=?", Integer.class, id())).isZero();
        assertThat(notificationCount()).isEqualTo(1);
        verify(redis, times(1)).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("대표 업무 이벤트는 BEFORE_COMMIT에 공통 Outbox로 전환되며 롤백·중복 계약을 유지한다")
    void giftEventUsesCommonOutboxBeforeCommit() {
        var event = new GiftCreatedEvent(12L, recipient, "상품");
        var tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE users SET name='전환 후' WHERE id=?", recipient);
            events.publishEvent(event);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, recipient)).isEqualTo("전환 전");
        assertThat(commonCount()).isZero();
        tx.executeWithoutResult(status -> {
            events.publishEvent(event);
            events.publishEvent(event);
            assertThat(commonCount()).isZero();
        });
        assertThat(commonCount()).isEqualTo(1);
        assertThat(notificationCount()).isZero();
        dispatcher.dispatch();
        dispatcher.dispatch();
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM notifications WHERE recipient_id=?", String.class, recipient))
            .isEqualTo(NotificationType.GIFT_RECEIVED.name());
    }

    @Test
    @DisplayName("공통 Outbox 저장 실패는 BEFORE_COMMIT 업무 변경을 함께 롤백한다")
    void writerFailureRollsBackBusinessTransaction() {
        doThrow(new CannotAcquireLockException("DB busy")).when(repository).insert(any());
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
            jdbc.update("UPDATE users SET name='전환 후' WHERE id=?", recipient);
            events.publishEvent(new GiftCreatedEvent(12L, recipient, "상품"));
        })).isInstanceOf(CannotAcquireLockException.class);
        assertThat(commonCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, recipient)).isEqualTo("전환 전");
    }

    @Test
    @DisplayName("알림 저장 후 완료 갱신 실패는 두 변경·Redis 신호를 롤백하고 다음 시도에 복구한다")
    void transientCompletionFailureRollsBackAndRecovers() {
        enqueue();
        doThrow(new CannotAcquireLockException("lock")).doCallRealMethod().when(repository).complete(any());
        dispatcher.dispatch();
        assertThat(notificationCount()).isZero();
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT failure_count FROM outbox_event WHERE id=?", Integer.class, id())).isEqualTo(1);
        assertThat(repository.findDueIds()).doesNotContain(id());
        verifyNoInteractions(redis);
        jdbc.update("UPDATE outbox_event SET next_attempt_at=NOW(6) WHERE id=?", id());
        dispatcher.dispatch();
        assertThat(state()).isEqualTo("PROCESSED");
        assertThat(notificationCount()).isEqualTo(1);
        verify(redis).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("잠긴 공통 작업은 기다리지 않고 건너뛰고 동시 기록은 UNIQUE로 한 건만 남긴다")
    void concurrentEnqueueAndSkipLockedProcessing() throws Exception {
        try (var executor = Executors.newFixedThreadPool(3)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 3; i++) futures.add(executor.submit(() -> { start.await(); enqueue(); return null; }));
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertThat(commonCount()).isEqualTo(1);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            doAnswer(call -> { entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                return call.callRealMethod(); }).when(creationTarget).saveInCurrentTransaction(any());
            Long jobId = id();
            var first = executor.submit(() -> transactions.process(jobId));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(executor.submit(() -> transactions.process(jobId)).get(2, TimeUnit.SECONDS))
                    .isEqualTo(NotificationCommonOutboxTransactionService.Outcome.SKIPPED);
            } finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(NotificationCommonOutboxTransactionService.Outcome.CREATED);
        }
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(state()).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("기존·공통 Worker가 같은 키를 동시에 저장해도 한 건만 생성하고 패배한 작업은 재시도로 완료한다")
    void concurrentLegacyAndCommonProcessingRecoversDeduplicationRace() throws Exception {
        new TransactionTemplate(manager).executeWithoutResult(tx -> legacyRepository.insert(command));
        enqueue();
        Long commonId = id();
        Long legacyId = jdbc.queryForObject("SELECT id FROM notification_outbox WHERE recipient_id=?", Long.class, recipient);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(creationTarget).saveInCurrentTransaction(any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var common = executor.submit(() -> dispatcher.dispatch());
            var legacy = executor.submit(() -> legacyDispatcher.dispatch());
            try { assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            common.get(10, TimeUnit.SECONDS);
            legacy.get(10, TimeUnit.SECONDS);
        }
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(state()).isIn("PENDING", "PROCESSED");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id=?", String.class, legacyId))
            .isIn("PENDING", "PROCESSED");
        jdbc.update("UPDATE outbox_event SET next_attempt_at=NOW(6) WHERE id=?", commonId);
        jdbc.update("UPDATE notification_outbox SET next_attempt_at=NOW(6) WHERE id=?", legacyId);
        dispatcher.dispatch();
        legacyDispatcher.dispatch();
        assertThat(state()).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id=?", String.class, legacyId))
            .isEqualTo("PROCESSED");
        verify(redis).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("알림 UNIQUE가 아닌 수신자 FK 오류는 중복으로 분류하지 않고 FAILED에 보존한다")
    void recipientForeignKeyFailureIsPermanentNotDeduplication() {
        command = new NotificationCreateCommand(Long.MAX_VALUE, command.sourceIdentifier(), command.type(),
            null, null, command.title(), command.message(), command.deduplicationKey());
        enqueue();
        dispatcher.dispatch();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(notificationCount()).isZero();
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("다른 이벤트의 eventId 충돌을 알림 멱등 키 중복으로 숨기지 않는다")
    void unrelatedEventIdCollisionFailsInsteadOfBeingIgnored() {
        UUID fixed = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_event(event_id,event_type,schema_version,deduplication_key,aggregate_type,aggregate_id,payload,occurred_at)"
            + " VALUES (?,'report.created.v1',1,?,'USER',?,JSON_OBJECT(),NOW(6))", fixed.toString(),
            command.deduplicationKey() + ":other", recipient.toString());
        try (var uuid = mockStatic(UUID.class, CALLS_REAL_METHODS)) {
            uuid.when(UUID::randomUUID).thenReturn(fixed);
            assertThatThrownBy(this::enqueue).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='notification.create.v1'"
            + " AND deduplication_key=?", Long.class, command.deduplicationKey())).isZero();
    }

    @Test
    @DisplayName("영구 payload 오류는 DB FAILED로 보존하고 지수 백오프는 300초 상한을 유지한다")
    void permanentPayloadAndUnlimitedTransientBackoffArePreserved() {
        enqueue();
        Long jobId = id();
        for (int i = 0; i < 12; i++) {
            transactions.recordFailure(jobId, true, CannotAcquireLockException.class.getName());
            assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,updated_at,next_attempt_at) FROM outbox_event WHERE id=?",
                Long.class, jobId)).isEqualTo(Math.min(300, 1L << i));
        }
        assertThat(state()).isEqualTo("PENDING");
        jdbc.update("UPDATE outbox_event SET payload=JSON_OBJECT(),next_attempt_at=NOW(6) WHERE id=?", jobId);
        dispatcher.dispatch();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(notificationCount()).isZero();
        assertThat(repository.findDueIds()).doesNotContain(jobId);
    }

    @Test
    @DisplayName("다른 이벤트 타입의 PENDING은 알림 Worker가 선점·완료·실패 갱신하지 않는다")
    void otherEventTypesAreUntouched() {
        enqueue();
        Long jobId = id();
        jdbc.update("UPDATE outbox_event SET event_type='report.created.v1' WHERE id=?", jobId);
        assertThat(transactions.process(jobId)).isEqualTo(NotificationCommonOutboxTransactionService.Outcome.SKIPPED);
        transactions.recordFailure(jobId, false, "WrongType");
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?", String.class, jobId)).isEqualTo("PENDING");
        assertThat(notificationCount()).isZero();
    }

}
