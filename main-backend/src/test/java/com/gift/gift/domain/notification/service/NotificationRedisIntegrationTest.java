package com.gift.gift.domain.notification.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class NotificationRedisIntegrationTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("sh", "-c", "while true; do "
                    + "while test -f /tmp/redis-paused; do sleep 0.1; done; "
                    + "redis-server --save '' --appendonly no; done");
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final java.util.List<Node> nodes = new java.util.ArrayList<>();
    private LettuceConnectionFactory publisherConnection;
    private StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @BeforeEach
    void setup() {
        publisherConnection = connection();
        redis = new StringRedisTemplate(publisherConnection);
    }

    @AfterEach
    void cleanup() throws Exception {
        for (Node node : nodes) node.close();
        publisherConnection.destroy();
    }

    @Test
    @DisplayName("독립 서버 두 대의 같은 사용자 모든 탭에 전달하고 무관한 서버는 구독하지 않는다")
    void userChannels_routeOnlyToInterestedNodes() throws Exception {
        Node a = node();
        Node b = node();
        Node unrelated = node();
        var a1 = new SseEmitterProbe(a.hub.connect(42L));
        var a2 = new SseEmitterProbe(a.hub.connect(42L));
        var b1 = new SseEmitterProbe(b.hub.connect(42L));
        var other = new SseEmitterProbe(unrelated.hub.connect(99L));
        awaitSubscribers(42L, 2);
        await().atMost(Duration.ofSeconds(5)).until(() -> a1.data.contains("resync") && b1.data.contains("resync"));
        awaitIdle(a, b, unrelated);
        publish(42L);
        a1.awaitNotifications(1);
        a2.awaitNotifications(1);
        b1.awaitNotifications(1);
        assertThat(other.notifications()).isEmpty();
        assertThat(unrelated.metrics.find("notification.redis.received").counters()).isEmpty();
        a1.complete();
        awaitSubscribers(42L, 2);
        a2.complete();
        awaitSubscribers(42L, 1);
        b1.complete();
        awaitSubscribers(42L, 0);
    }

    @Test
    @DisplayName("Redis 구독 연결 단절 후 실제 재구독에서 resync하고 새 알림 수신을 재개한다")
    void redisReconnect_resubscribesAndResyncs() throws Exception {
        Node a = node();
        var probe = new SseEmitterProbe(a.hub.connect(42L));
        await().atMost(Duration.ofSeconds(10)).until(() -> probe.data.contains("resync"));
        long previous = probe.data.stream().filter("resync"::equals).count();
        // 테스트 Redis에서 구독용 TCP 연결만 끊는다. PING 성공을 복구 판정으로 사용하지 않는다.
        var result = REDIS.execInContainer("redis-cli", "CLIENT", "KILL", "TYPE", "pubsub");
        assertThat(result.getExitCode()).isZero();
        assertThat(Integer.parseInt(result.getStdout().trim())).isGreaterThanOrEqualTo(1);
        await().atMost(Duration.ofSeconds(15)).until(
                () -> probe.data.stream().filter("resync"::equals).count() > previous);
        awaitSubscribers(42L, 1);
        awaitIdle(a);
        publish(42L);
        probe.awaitNotifications(1);
        assertThat(probe.notifications()).containsExactly(NotificationSseHubTest.event(42L).notification());
    }

    @Test
    @DisplayName("연결 해제 중 발행한 과거 알림은 새 구독에서 재생하지 않는다")
    void reconnect_doesNotReplayOldNotifications() throws Exception {
        Node a = node();
        var old = new SseEmitterProbe(a.hub.connect(42L));
        awaitSubscribers(42L, 1);
        old.complete();
        awaitSubscribers(42L, 0);
        publish(42L);
        var fresh = new SseEmitterProbe(a.hub.connect(42L));
        await().atMost(Duration.ofSeconds(10)).until(() -> fresh.data.contains("resync"));
        assertThat(fresh.notifications()).isEmpty();
        awaitIdle(a);
        publish(42L);
        fresh.awaitNotifications(1);
    }

    @Test
    @DisplayName("Redis 중단 중 추가된 사용자 채널도 서버 복구 후 구독하고 resync한다")
    void redisOutage_restoresExistingAndNewUserChannels() throws Exception {
        Node a = node();
        var existing = new SseEmitterProbe(a.hub.connect(42L));
        await().atMost(Duration.ofSeconds(10)).until(() -> existing.data.contains("resync"));
        long previous = existing.data.stream().filter("resync"::equals).count();
        // Docker 컨테이너 재시작은 임의 호스트 포트를 바꾼다. 네트워크 주소를 유지하고 Redis 프로세스만 중단한다.
        assertThat(REDIS.execInContainer("touch", "/tmp/redis-paused").getExitCode()).isZero();
        REDIS.execInContainer("redis-cli", "SHUTDOWN", "NOSAVE");
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var newConnection = executor.submit(() -> new SseEmitterProbe(a.hub.connect(99L)));
            try {
                await().atMost(Duration.ofSeconds(5)).until(() ->
                        a.metrics.get("notification.sse.connections").gauge().value() == 2);
            } finally {
                assertThat(REDIS.execInContainer("rm", "/tmp/redis-paused").getExitCode()).isZero();
            }
            SseEmitterProbe fresh;
            try {
                fresh = newConnection.get(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException failedDuringOutage) {
                await().atMost(Duration.ofSeconds(10)).until(() ->
                        REDIS.execInContainer("redis-cli", "PING").getStdout().trim().equals("PONG"));
                fresh = new SseEmitterProbe(a.hub.connect(99L));
            }
            final var recovered = fresh;
            await().atMost(Duration.ofSeconds(20)).until(() -> recovered.data.contains("resync")
                    && existing.data.stream().filter("resync"::equals).count() > previous);
            awaitSubscribers(42L, 1);
            awaitSubscribers(99L, 1);
            awaitIdle(a);
            publish(42L);
            publish(99L);
            existing.awaitNotifications(1);
            fresh.awaitNotifications(1);
        }
    }

    @Test
    @DisplayName("최초 Redis 구독 실패는 유령 연결을 남기지 않고 재접속으로 복구한다")
    void initialSubscriptionFailure_cleansUpAndAllowsReconnect() throws Exception {
        Node a = node();
        REDIS.execInContainer("touch", "/tmp/redis-paused");
        REDIS.execInContainer("redis-cli", "SHUTDOWN", "NOSAVE");
        try {
            assertThatThrownBy(() -> a.hub.connect(42L)).isInstanceOf(RuntimeException.class);
            assertThat(a.hub.hasConnections()).isFalse();
            assertThat(a.metrics.get("notification.redis.user.channels").gauge().value()).isZero();
        } finally {
            REDIS.execInContainer("rm", "/tmp/redis-paused");
        }
        await().atMost(Duration.ofSeconds(10)).until(() ->
                REDIS.execInContainer("redis-cli", "PING").getStdout().trim().equals("PONG"));
        var fresh = new SseEmitterProbe(a.hub.connect(42L));
        await().atMost(Duration.ofSeconds(10)).until(() -> fresh.data.contains("resync"));
        awaitSubscribers(42L, 1);
        awaitIdle(a);
        publish(42L);
        fresh.awaitNotifications(1);
    }

    @Test
    @DisplayName("최초 구독 목록 확보 후 다른 사용자가 연결돼도 두 채널을 모두 구독한다")
    void concurrentInitialSubscriptions_doNotSkipLateChannel() throws Exception {
        var captured = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379)) {
            @Override
            public org.springframework.data.redis.connection.RedisConnection getConnection() {
                if (first.compareAndSet(true, false)) {
                    captured.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("구독 재개 시간 초과");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
                return super.getConnection();
            }
        };
        factory.afterPropertiesSet();
        factory.start();
        Node a = node(factory);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<SseEmitterProbe> firstConnection = executor.submit(() -> new SseEmitterProbe(a.hub.connect(42L)));
            Future<SseEmitterProbe> secondConnection;
            var secondThread = new AtomicReference<Thread>();
            try {
                assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue();
                secondConnection = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    return new SseEmitterProbe(a.hub.connect(99L));
                });
                await().atMost(Duration.ofSeconds(5)).until(() -> {
                    Thread thread = secondThread.get();
                    return a.metrics.get("notification.sse.connections").gauge().value() == 2
                            && thread != null && (thread.getState() == Thread.State.BLOCKED
                            || thread.getState() == Thread.State.WAITING);
                });
            } finally {
                release.countDown();
            }
            var firstProbe = firstConnection.get(10, TimeUnit.SECONDS);
            var secondProbe = secondConnection.get(10, TimeUnit.SECONDS);
            awaitSubscribers(42L, 1);
            awaitSubscribers(99L, 1);
            await().atMost(Duration.ofSeconds(5)).until(() -> firstProbe.data.contains("resync")
                    && secondProbe.data.contains("resync"));
            awaitIdle(a);
            publish(42L);
            publish(99L);
            firstProbe.awaitNotifications(1);
            secondProbe.awaitNotifications(1);
            assertThat(firstProbe.notifications()).containsExactly(NotificationSseHubTest.event(42L).notification());
            assertThat(secondProbe.notifications()).containsExactly(NotificationSseHubTest.event(99L).notification());
        }
    }

    @Test
    @DisplayName("여러 사용자 동시 연결과 전체 해제를 반복해도 누락 중복 잔존 구독이 없다")
    void concurrentUsers_reconnectAfterAllSubscriptionsRemoved() throws Exception {
        Node a = node();
        try (var executor = Executors.newFixedThreadPool(24)) {
            for (int round = 0; round < 3; round++) {
                var start = new CountDownLatch(1);
                var tasks = new ArrayList<Future<SseEmitterProbe>>();
                for (long user = 1; user <= 24; user++) {
                    long recipient = user;
                    tasks.add(executor.submit(() -> {
                        start.await();
                        return new SseEmitterProbe(a.hub.connect(recipient));
                    }));
                }
                start.countDown();
                var probes = new ArrayList<SseEmitterProbe>();
                for (var task : tasks) probes.add(task.get(15, TimeUnit.SECONDS));
                for (long user = 1; user <= 24; user++) awaitSubscribers(user, 1);
                await().atMost(Duration.ofSeconds(5)).until(() -> probes.stream()
                        .allMatch(probe -> probe.data.contains("resync")));
                awaitIdle(a);
                for (long user = 1; user <= 24; user++) publish(user);
                for (var probe : probes) probe.awaitNotifications(1);
                awaitIdle(a);
                for (int i = 0; i < probes.size(); i++) {
                    assertThat(probes.get(i).notifications())
                            .containsExactly(NotificationSseHubTest.event((long) i + 1).notification());
                }
                var removals = new ArrayList<Future<?>>();
                for (var probe : probes) removals.add(executor.submit(probe::complete));
                for (var removal : removals) removal.get(10, TimeUnit.SECONDS);
                assertThat(a.hub.hasConnections()).isFalse();
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(REDIS.execInContainer(
                        "redis-cli", "--raw", "PUBSUB", "CHANNELS",
                        "notifications:user:*").getStdout().trim()).isEmpty());
            }
        }
    }

    private void publish(Long user) {
        redis.convertAndSend(NotificationRedisPublisher.channel(user),
                mapper.writeValueAsString(NotificationSseHubTest.event(user)));
    }

    private void awaitSubscribers(Long user, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var result = REDIS.execInContainer("redis-cli", "--raw", "PUBSUB", "NUMSUB",
                    NotificationRedisPublisher.channel(user));
            assertThat(result.getStdout().trim().split("\\R")[1]).isEqualTo(Integer.toString(count));
        });
    }

    private void awaitIdle(Node... values) {
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            for (Node node : values) {
                var executor = (java.util.concurrent.ThreadPoolExecutor)
                        org.springframework.test.util.ReflectionTestUtils.getField(node.hub, "sender");
                if (executor.getActiveCount() != 0 || !executor.getQueue().isEmpty()) return false;
            }
            return true;
        });
    }

    private LettuceConnectionFactory connection() {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private Node node() throws Exception {
        return node(connection());
    }

    private Node node(LettuceConnectionFactory factory) throws Exception {
        var config = new NotificationRedisConfig();
        var executor = config.notificationRedisSubscriptionExecutor();
        var container = config.notificationRedisContainer(factory, executor);
        container.afterPropertiesSet();
        container.start();
        var metrics = new SimpleMeterRegistry();
        var node = new Node(factory, container, executor, metrics, new NotificationSseHub(container, mapper, metrics));
        nodes.add(node);
        return node;
    }

    private record Node(LettuceConnectionFactory factory, RedisMessageListenerContainer container,
                        SimpleAsyncTaskExecutor executor, SimpleMeterRegistry metrics, NotificationSseHub hub) {
        void close() throws Exception {
            hub.destroy();
            container.destroy();
            executor.close();
            factory.destroy();
            metrics.close();
        }
    }
}
