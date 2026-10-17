package com.gift.gift.domain.notification.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.repository.NotificationOutboxRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "app.notification.outbox.batch-size=20")
class NotificationOutboxIntegrationTest {
    @Autowired private NotificationOutboxWriter writer;
    @Autowired private NotificationOutboxDispatcher dispatcher;
    @Autowired private NotificationOutboxTransactionService transactions;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private NotificationOutboxRepository repository;
    @MockitoSpyBean private NotificationCreateTransactionService createService;
    private NotificationCreateTransactionService createTarget;
    private Long recipientId;
    private NotificationCreateCommand command;

    @BeforeEach
    void setUp() {
        createTarget = AopTestUtils.getUltimateTargetObject(createService);
        recipientId = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53), "Outbox 테스트", LocalDate.of(1990, 1, 1))).getId();
        command = new NotificationCreateCommand(recipientId, "test", NotificationType.GIFT_RECEIVED,
                null, null, "알림", "내용", "outbox:" + UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_id = ?", recipientId);
        jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", recipientId);
        jdbc.update("DELETE FROM users WHERE id = ?", recipientId);
    }

    @Test
    @DisplayName("업무 커밋 후에만 Outbox가 남고 워커가 알림과 완료를 함께 저장한다")
    void committedJobIsProcessedOnce() {
        enqueue();
        assertThat(notificationCount()).isZero();
        assertThat(status()).isEqualTo("PENDING");
        dispatcher.dispatch();
        dispatcher.dispatch();
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(status()).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("업무 롤백은 Outbox도 롤백한다")
    void rollbackRemovesJob() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            writer.enqueue(command);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id = ?",
                Integer.class, recipientId)).isZero();
    }

    @Test
    @DisplayName("동일 이벤트를 반복 기록해도 작업과 알림이 한 건만 생긴다")
    void duplicateEnqueueIsIdempotent() {
        enqueue();
        enqueue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id = ?",
                Integer.class, recipientId)).isEqualTo(1);
        dispatcher.dispatch();
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("알림 삽입 후 완료 기록 실패는 둘 다 롤백하고 다음 실행에서 복구한다")
    void transientFailureRollsBackAndRecovers() {
        enqueue();
        doThrow(new CannotAcquireLockException("test lock failure")).doCallRealMethod().when(repository).complete(any());
        dispatcher.dispatch();
        assertThat(notificationCount()).isZero();
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT failure_count FROM notification_outbox WHERE id = ?",
                Integer.class, jobId())).isEqualTo(1);
        assertThat(repository.findDueIds()).doesNotContain(jobId());
        jdbc.update("UPDATE notification_outbox SET next_attempt_at = CURRENT_TIMESTAMP(6) WHERE id = ?", jobId());
        dispatcher.dispatch();
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(status()).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("데이터 오류는 무한 재시도하지 않고 FAILED로 보존한다")
    void permanentFailureIsRetained() {
        enqueue();
        doThrow(new DataIntegrityViolationException("test invalid data"))
                .when(createTarget).saveInCurrentTransaction(any());
        dispatcher.dispatch();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(notificationCount()).isZero();
        assertThat(repository.findDueIds()).doesNotContain(jobId());
    }

    @Test
    @DisplayName("다른 워커가 잠근 작업은 기다리지 않고 건너뛴다")
    void concurrentWorkerSkipsLockedJob() throws Exception {
        enqueue();
        Long id = jobId();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            locked.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(createTarget).saveInCurrentTransaction(any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transactions.process(id));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                executor.submit(() -> transactions.process(id)).get(3, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(status()).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("같은 이벤트의 동시 기록도 UNIQUE로 한 작업만 남긴다")
    void concurrentEnqueueCreatesOneJob() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 3; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    enqueue();
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id = ?",
                Integer.class, recipientId)).isEqualTo(1);
        dispatcher.dispatch();
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("새 dispatcher도 DB의 대기 작업을 읽고 한 번에 최대 20건을 처리한다")
    void freshDispatcherDrainsDurableBacklogInBatches() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (int i = 0; i < 25; i++) {
                writer.enqueue(new NotificationCreateCommand(recipientId, "test-" + i, command.type(),
                        null, null, command.title(), command.message(), command.deduplicationKey() + ":" + i));
            }
        });
        var freshDispatcher = new NotificationOutboxDispatcher(repository, transactions);
        freshDispatcher.dispatch();
        assertThat(notificationCount()).isEqualTo(20);
        freshDispatcher.dispatch();
        assertThat(notificationCount()).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id = ?"
                + " AND status = 'PROCESSED'", Integer.class, recipientId)).isEqualTo(25);
    }

    @Test
    @DisplayName("기존 알림이 있으면 새 알림 저장 없이 Outbox만 완료한다")
    void existingNotificationCompletesJobWithoutPublishingAgain() {
        createService.save(command);
        enqueue();
        dispatcher.dispatch();
        verify(createTarget, never()).saveInCurrentTransaction(any());
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(status()).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("반복 실패의 간격은 늘어나고 최대 300초를 넘지 않는다")
    void retryBackoffIsDurableAndCapped() {
        enqueue();
        Long id = jobId();
        for (int i = 0; i < 11; i++) {
            transactions.recordFailure(id, true, CannotAcquireLockException.class.getName());
            long delay = jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, updated_at, next_attempt_at)"
                    + " FROM notification_outbox WHERE id = ?", Long.class, id);
            assertThat(delay).isEqualTo(Math.min(300, 1L << i));
        }
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT failure_count FROM notification_outbox WHERE id = ?",
                Integer.class, id)).isEqualTo(11);
    }

    @Test
    @DisplayName("Outbox의 FK 오류는 중복 처리로 숨겨지지 않고 같은 트랜잭션을 롤백한다")
    void invalidRecipientIsNotSilentlyIgnored() {
        var invalid = new NotificationCreateCommand(-1L, "missing", command.type(), null, null,
                command.title(), command.message(), command.deduplicationKey() + ":invalid");
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            writer.enqueue(command);
            writer.enqueue(invalid);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_id = ?",
                Integer.class, recipientId)).isZero();
    }

    @Test
    @DisplayName("실패 메타데이터 기록마저 실패해도 PENDING 작업은 남아 다음 실행에서 복구한다")
    void bookkeepingFailureDoesNotLosePendingJob() {
        enqueue();
        doThrow(new CannotAcquireLockException("test lock failure")).doCallRealMethod().when(repository).complete(any());
        doThrow(new DataAccessResourceFailureException("test DB unavailable"))
                .doCallRealMethod().when(repository).recordFailure(any(), anyBoolean(), any());
        dispatcher.dispatch();
        assertThat(status()).isEqualTo("PENDING");
        assertThat(notificationCount()).isZero();
        dispatcher.dispatch();
        assertThat(status()).isEqualTo("PROCESSED");
        assertThat(notificationCount()).isEqualTo(1);
        transactions.recordFailure(jobId(), true, "late failure");
        assertThat(status()).isEqualTo("PROCESSED");
    }

    private void enqueue() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> writer.enqueue(command));
    }

    private Long jobId() {
        return jdbc.queryForObject("SELECT id FROM notification_outbox WHERE deduplication_key = ?",
                Long.class, command.deduplicationKey());
    }

    private int notificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ?", Integer.class, recipientId);
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String.class, jobId());
    }
}
