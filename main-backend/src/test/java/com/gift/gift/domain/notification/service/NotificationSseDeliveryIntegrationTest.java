package com.gift.gift.domain.notification.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gift.gift.domain.notification.dto.NotificationCreateCommand;
import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;
import com.gift.gift.domain.notification.repository.NotificationSseDeliveryRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class NotificationSseDeliveryIntegrationTest {
    @Autowired private NotificationService notifications;
    @Autowired private NotificationCreateTransactionService creation;
    @Autowired private NotificationSseConnectionService connections;
    @Autowired private NotificationSseDeliveryService deliveries;
    @Autowired private NotificationSseDeliveryRepository repository;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private Long recipient;
    private final String nodeA = UUID.randomUUID().toString();
    private final String nodeB = UUID.randomUUID().toString();

    @BeforeEach
    void setup() {
        recipient = users.saveAndFlush(new User("sse-" + UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53), "SSE 사용자", LocalDate.of(1990, 1, 1))).getId();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", recipient);
        jdbc.update("DELETE FROM users WHERE id = ?", recipient);
    }

    private String connect(String node) {
        String id = UUID.randomUUID().toString();
        connections.register(id, node, recipient);
        return id;
    }

    private NotificationCreateCommand command() {
        return new NotificationCreateCommand(recipient, "sse-test", NotificationType.GIFT_RECEIVED,
                NotificationReferenceType.GIFT, 501L, "선물이 도착했어요", "새 선물이 도착했습니다.",
                "SSE_TEST:" + UUID.randomUUID());
    }

    @Test
    @DisplayName("다른 서버에서 저장한 알림을 동일 사용자의 두 서버 연결 모두 조회한다")
    void create_fansOutToBothNodes() {
        connect(nodeA);
        connect(nodeB);
        notifications.create(command());
        var a = deliveries.findDue(nodeA, 100);
        var b = deliveries.findDue(nodeB, 100);
        assertThat(a).hasSize(1);
        assertThat(b).hasSize(1);
        assertThat(a.getFirst().notification().notificationId()).isEqualTo(b.getFirst().notification().notificationId());
        assertThat(a.getFirst().notification().title()).isEqualTo("선물이 도착했어요");
        assertThat(a.getFirst().id()).isNotEqualTo(b.getFirst().id());
    }

    @Test
    @DisplayName("롤백된 알림과 전달 작업은 함께 사라진다")
    void rollback_removesNotificationAndDelivery() {
        connect(nodeA);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            creation.saveInCurrentTransaction(command());
            status.setRollbackOnly();
        });
        assertThat(deliveries.findDue(nodeA, 100)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ?",
                Long.class, recipient)).isZero();
    }

    @Test
    @DisplayName("전송 전 완료는 담당 서버에서 한 번만 가능하며 다시 조회하지 않는다")
    void completeBeforeSend_stopsFutureDelivery() {
        connect(nodeA);
        notifications.create(command());
        long id = deliveries.findDue(nodeA, 100).getFirst().id();
        assertThat(deliveries.completeBeforeSend(id, nodeB)).isFalse();
        assertThat(deliveries.completeBeforeSend(id, nodeA)).isTrue();
        var completed = jdbc.queryForObject("SELECT completed_at FROM notification_sse_deliveries WHERE id = ?",
                Timestamp.class, id);
        assertThat(completed).isNotNull();
        assertThat(deliveries.completeBeforeSend(id, nodeA)).isFalse();
        jdbc.update("UPDATE notification_sse_deliveries SET next_attempt_at = NOW(6) WHERE id = ?", id);
        assertThat(deliveries.findDue(nodeA, 100)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT completed_at FROM notification_sse_deliveries WHERE id = ?",
                Timestamp.class, id)).isEqualTo(completed);
    }

    @Test
    @DisplayName("배치 크기를 초과해도 다음 조회에서 나머지를 처리한다")
    void batch_drainsWithoutCursorLoss() {
        connect(nodeA);
        for (int i = 0; i < 5; i++) notifications.create(command());
        int count = 0;
        while (true) {
            var batch = deliveries.findDue(nodeA, 2);
            if (batch.isEmpty()) break;
            assertThat(batch).hasSizeLessThanOrEqualTo(2);
            for (var item : batch) deliveries.completeBeforeSend(item.id(), nodeA);
            count += batch.size();
        }
        assertThat(count).isEqualTo(5);
    }

    @Test
    @DisplayName("연결 종료 시 미확인 작업을 정리하고 새 연결에는 새 알림만 전달한다")
    void reconnect_doesNotReplayDisconnectedJobs() {
        String old = connect(nodeA);
        notifications.create(command());
        connections.disconnect(old);
        connect(nodeB);
        assertThat(deliveries.findDue(nodeA, 100)).isEmpty();
        assertThat(deliveries.findDue(nodeB, 100)).isEmpty();
        notifications.create(command());
        assertThat(deliveries.findDue(nodeB, 100)).hasSize(1);
    }

    @Test
    @DisplayName("만료 연결은 갱신으로 되살아나지 않으며 전달 작업도 정리한다")
    void expiredLease_isRemoved() {
        String id = connect(nodeA);
        notifications.create(command());
        jdbc.update("UPDATE notification_sse_connections SET expires_at = NOW(6) WHERE id = ?", id);
        assertThat(connections.renew(nodeA)).isEmpty();
        assertThat(deliveries.findDue(nodeA, 100)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_sse_deliveries WHERE node_id = ?",
                Long.class, nodeA)).isZero();
    }

    @Test
    @DisplayName("같은 전달 작업의 동시 완료은 한 번만 성공한다")
    void completeBeforeSend_allowsOnlyOneConcurrentSender() throws Exception {
        connect(nodeA);
        notifications.create(command());
        long id = deliveries.findDue(nodeA, 100).getFirst().id();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return deliveries.completeBeforeSend(id, nodeA);
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    @DisplayName("낮은 ID가 늦게 커밋되어도 다음 폴링에서 전달한다")
    void lateCommit_isNotSkipped() throws Exception {
        Long other = users.saveAndFlush(new User("sse-" + UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53), "다른 사용자", LocalDate.of(1990, 1, 1))).getId();
        connections.register(UUID.randomUUID().toString(), nodeA, other);
        connect(nodeA);
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var original = command();
            var delayed = new NotificationCreateCommand(other, original.sourceIdentifier(), original.type(),
                    original.referenceType(), original.referenceId(), original.title(), original.message(),
                    original.deduplicationKey());
            Future<?> slow = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                creation.saveInCurrentTransaction(delayed);
                inserted.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            try {
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
                notifications.create(command());
                var first = deliveries.findDue(nodeA, 100);
                assertThat(first).hasSize(1);
                deliveries.completeBeforeSend(first.getFirst().id(), nodeA);
                release.countDown();
                slow.get(10, TimeUnit.SECONDS);
                var late = deliveries.findDue(nodeA, 100);
                assertThat(late).hasSize(1);
                assertThat(late.getFirst().id()).isLessThan(first.getFirst().id());
                assertThat(late.getFirst().notification().notificationId()).isLessThan(first.getFirst().notification().notificationId());
            } finally { release.countDown(); }
        } finally {
            jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", other);
            jdbc.update("DELETE FROM users WHERE id = ?", other);
        }
    }

    @Test
    @DisplayName("두 서버의 독립 허브와 폴러가 각 연결에 같은 알림을 전송한다")
    void poll_sendsThroughBothIndependentHubs() throws Exception {
        var a = new NotificationSseHub(connections);
        var b = new NotificationSseHub(connections);
        var emitterA = a.connect(recipient);
        var emitterB = b.connect(recipient);
        var receivedA = receive(emitterA);
        var receivedB = receive(emitterB);
        try {
            notifications.create(command());
            new NotificationSseDeliveryScheduler(deliveries, a).poll();
            new NotificationSseDeliveryScheduler(deliveries, b).poll();
            var notificationA = receivedA.get(5, TimeUnit.SECONDS);
            var notificationB = receivedB.get(5, TimeUnit.SECONDS);
            assertThat(notificationA).isEqualTo(notificationB);
            assertThat(notificationA.title()).isEqualTo("선물이 도착했어요");
            assertThat(notificationA.message()).isEqualTo("새 선물이 도착했습니다.");
            assertThat(notificationA.referenceId()).isEqualTo(501L);
        } finally { a.destroy(); b.destroy(); }
    }

    @Test
    @DisplayName("같은 연결의 전송이 막히면 후속 전달을 포기하고 완료 상태와 연결을 유지한다")
    void busyConnection_completesDroppedJobsWithoutDisconnecting() throws Exception {
        var hub = new NotificationSseHub(connections);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var received = receive(hub.connect(recipient), entered, release);
        try {
            notifications.create(command());
            notifications.create(command());
            var poller = new NotificationSseDeliveryScheduler(deliveries, hub);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), poller::poll);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), hub::sendHeartbeats);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_sse_deliveries"
                    + " WHERE node_id = ? AND completed_at IS NOT NULL", Long.class, hub.nodeId())).isEqualTo(2);
            assertThat(deliveries.findDue(hub.nodeId(), 100)).isEmpty();
            assertThat(hub.hasConnections()).isTrue();
            poller.poll();
            release.countDown();
            received.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            hub.destroy();
        }
    }

    @Test
    @DisplayName("전송 큐 거절 전에 완료가 커밋되며 다음 폴링에서 재시도하지 않는다")
    void rejectedSend_doesNotRetryCompletedDelivery() {
        String connection = connect(nodeA);
        notifications.create(command());
        long id = deliveries.findDue(nodeA, 100).getFirst().id();
        var hub = mock(NotificationSseHub.class);
        when(hub.nodeId()).thenReturn(nodeA);
        when(hub.hasConnections()).thenReturn(true);
        when(hub.send(eq(connection),
                eq(id), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT completed_at FROM notification_sse_deliveries WHERE id = ?",
                    Timestamp.class, id)).isNotNull();
            return false;
        });
        var poller = new NotificationSseDeliveryScheduler(deliveries, hub);
        poller.poll();
        poller.poll();
        verify(hub, times(1)).send(
                eq(connection), eq(id),
                any());
        assertThat(deliveries.findDue(nodeA, 100)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ?",
                Long.class, recipient)).isEqualTo(1);
    }

    private CompletableFuture<com.gift.gift.domain.notification.dto.response.NotificationListItem> receive(
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) throws Exception {
        return receive(emitter, null, null);
    }

    private CompletableFuture<com.gift.gift.domain.notification.dto.response.NotificationListItem> receive(
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
            CountDownLatch entered, CountDownLatch release) throws Exception {
        var received = new CompletableFuture<com.gift.gift.domain.notification.dto.response.NotificationListItem>();
        Class<?> handlerType = Class.forName(
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
        Object handler = java.lang.reflect.Proxy.newProxyInstance(handlerType.getClassLoader(),
                new Class<?>[]{handlerType}, (proxy, method, arguments) -> {
                    if (method.getName().equals("send") && arguments[0] instanceof java.util.Set<?> items) {
                        for (Object item : items) {
                            Object data = ((org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
                                    .DataWithMediaType) item).getData();
                            if (data instanceof com.gift.gift.domain.notification.dto.response.NotificationListItem n) {
                                if (entered != null) {
                                    entered.countDown();
                                    release.await(10, TimeUnit.SECONDS);
                                }
                                received.complete(n);
                            }
                        }
                    }
                    return null;
                });
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(emitter, "initialize", handler);
        return received;
    }

    @Test
    @DisplayName("연결 등록이 수신자 잠금을 선점하면 대기 중 알림도 등록 커밋 후 전달 작업을 만든다")
    void registrationBoundary_doesNotLoseNotification() throws Exception {
        var registered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> registration = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(s -> {
                repository.lockRecipient(recipient);
                repository.register(UUID.randomUUID().toString(), nodeA, recipient);
                registered.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            assertThat(registered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> notification = executor.submit(() -> notifications.create(command()));
            try {
                assertThat(deliveries.findDue(nodeA, 100)).isEmpty();
            } finally { release.countDown(); }
            registration.get(10, TimeUnit.SECONDS);
            notification.get(10, TimeUnit.SECONDS);
            assertThat(deliveries.findDue(nodeA, 100)).hasSize(1);
        }
    }
}
