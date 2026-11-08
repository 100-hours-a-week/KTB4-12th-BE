package com.gift.gift.domain.bugreport.messaging;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.messaging.*;
import com.gift.gift.global.outbox.OutboxProperties;
import com.gift.gift.infrastructure.discord.DiscordWebhookClient;

import static com.gift.gift.domain.bugreport.messaging.ReportMqProperties.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportDiscordBrokerIntegrationTest {
    private final GenericContainer<?> broker = new GenericContainer<>("rabbitmq:4.4.0-management").withExposedPorts(5672);
    private final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private final ObjectMapper mapper = new ObjectMapper();
    private CachingConnectionFactory connection;
    private LettuceConnectionFactory redisConnection;
    private StringRedisTemplate redisTemplate;
    private RabbitTemplate template;
    private RabbitAdmin admin;
    private RabbitPublisher publisher;
    private ReportDiscordListener listener;
    private SimpleMessageListenerContainer container;
    private final DiscordWebhookClient discord = mock(DiscordWebhookClient.class);

    @BeforeAll
    void startServices() {
        broker.start();
        redis.start();
        connection = new CachingConnectionFactory(broker.getHost(), broker.getMappedPort(5672));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        connection.getRabbitConnectionFactory().setRequestedHeartbeat(2);
        template = new RabbitPublisherConfiguration().outboxRabbitTemplate(connection);
        admin = new RabbitAdmin(connection);
        for (String exchange : List.of(EVENTS, RETRY, DLX)) admin.declareExchange(new TopicExchange(exchange));
        admin.declareQueue(QueueBuilder.durable(DISCORD_QUEUE).build());
        admin.declareQueue(QueueBuilder.durable(SHEETS_QUEUE).build());
        admin.declareQueue(QueueBuilder.durable(RETRY_QUEUE).ttl(30000).deadLetterExchange(EVENTS).build());
        admin.declareQueue(QueueBuilder.durable(DLQ).build());
        bind(DISCORD_QUEUE, EVENTS, DISCORD);
        bind(SHEETS_QUEUE, EVENTS, SHEETS);
        bind(RETRY_QUEUE, RETRY, DISCORD);
        bind(DLQ, DLX, "Report.#");
        publisher = new RabbitPublisher(template, mapper, new OutboxProperties(10, 60, 5000, 10, 2, 300),
            new RabbitRoutingProperties("other.events", "other.key"));
        redisConnection = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        redisConnection.afterPropertiesSet();
        redisConnection.start();
        redisTemplate = new StringRedisTemplate(redisConnection);
    }

    private void bind(String queue, String exchange, String key) {
        admin.declareBinding(new Binding(queue, Binding.DestinationType.QUEUE, exchange, key, null));
    }

    @BeforeEach
    void resetServices() {
        reset(discord);
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
        for (String queue : List.of(DISCORD_QUEUE, SHEETS_QUEUE, RETRY_QUEUE, DLQ)) admin.purgeQueue(queue);
    }

    @AfterEach
    void stopListener() {
        if (listener != null) listener.destroy();
        listener = null;
    }

    @AfterAll
    void stopServices() {
        if (redisConnection != null) redisConnection.destroy();
        if (connection != null) connection.destroy();
        redis.stop();
        broker.stop();
    }

    private void startListener(RabbitPublisher selectedPublisher, ReportDiscordCooldown cooldown) {
        container = new SimpleMessageListenerContainer(connection);
        container.setQueueNames(DISCORD_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(1);
        container.setAutoDeclare(false);
        container.setShutdownTimeout(30000);
        container.setForceStop(true);
        container.setForceCloseChannel(true);
        listener = new ReportDiscordListener(container, mock(ReportTopologyPreflight.class));
        container.setMessageListener(new ReportDiscordConsumer(mapper, discord, "https://discord.test/api/webhooks/1/token",
            cooldown, selectedPublisher, new SimpleMeterRegistry(), listener::recover, listener::recovered));
        listener.start();
    }

    private ReportDiscordCooldown cooldown() {
        return new ReportDiscordCooldown(redisTemplate, "https://discord.test/api/webhooks/1/token");
    }

    @Test
    @DisplayName("실제 브로커에서 실패 메시지는 30초 후 Discord에만 복귀해 성공한다")
    void retryReturnsAfterThirtySecondsOnlyToDiscord() throws Exception {
        CountDownLatch success = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(discord.send(anyString(), anyString())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) return failure();
            success.countDown();
            return success();
        });
        startListener(publisher, cooldown());
        long start = System.nanoTime();
        template.send(EVENTS, DISCORD, ReportDiscordConsumerTest.event(mapper));
        assertThat(success.await(40, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isGreaterThanOrEqualTo(29000);
        listener.stop();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(template.receive(SHEETS_QUEUE, 100)).isNull();
        assertThat(template.receive(DISCORD_QUEUE, 100)).isNull();
    }

    @Test
    @DisplayName("실제 mandatory return에서 원본이 보존되고 5초 복구 뒤 재전달된다")
    void transferFailureClosesChannelAndRecoversOriginal() throws Exception {
        RabbitPublisher unavailable = mock(RabbitPublisher.class);
        AtomicInteger publications = new AtomicInteger();
        when(unavailable.publish(any(Message.class), anyString(), anyString(), anyString())).thenAnswer(call -> {
            int attempt = publications.incrementAndGet();
            return publisher.publish(call.getArgument(0, Message.class), "transfer-" + attempt, EVENTS,
                attempt == 1 ? "missing.binding" : DISCORD);
        });
        when(discord.send(anyString(), anyString())).thenReturn(failure(), success());
        startListener(unavailable, cooldown());
        template.send(EVENTS, DISCORD, ReportDiscordConsumerTest.event(mapper));
        long start = System.nanoTime();
        await(() -> mockingDetails(discord).getInvocations().size() >= 2, 15);
        listener.stop();
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isGreaterThanOrEqualTo(4500);
        verify(discord, times(2)).send(anyString(), anyString());
        assertThat(template.receive(DISCORD_QUEUE, 100)).isNull();
    }

    @Test
    @DisplayName("Redis 장애 시 외부 호출 없이 원본을 재시도 큐로 보존한다")
    void redisFailureDefersWithoutCallingDiscord() throws Exception {
        ReportDiscordCooldown unavailable = mock(ReportDiscordCooldown.class);
        when(unavailable.remainingMs(0)).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        startListener(publisher, unavailable);
        Message original = ReportDiscordConsumerTest.event(mapper);
        template.send(EVENTS, DISCORD, original);
        Message retry = template.receive(RETRY_QUEUE, 5000);
        assertThat(retry).isNotNull();
        assertThat(retry.getBody()).isEqualTo(original.getBody());
        assertThat(retry.getMessageProperties().getHeaders()).containsEntry("delivery.retryCount", 0);
        verifyNoInteractions(discord);
    }

    @Test
    @DisplayName("깨진 JSON을 공용 DLQ에 원문 그대로 저장한다")
    void preservesMalformedBodyInDlq() {
        startListener(publisher, cooldown());
        template.send(EVENTS, DISCORD, new Message("{broken".getBytes(), new MessageProperties()));
        Message dead = template.receive(DLQ, 5000);
        assertThat(dead).isNotNull();
        assertThat(dead.getBody()).isEqualTo("{broken".getBytes());
        assertThat(dead.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(dead.getMessageProperties().getHeaders()).containsEntry("delivery.channel", "discord");
        verifyNoInteractions(discord);
    }

    @Test
    @DisplayName("종료는 진행 중 성공을 기다리고 신규 메시지를 소비하지 않는다")
    void gracefulStopAcknowledgesInFlightAndLeavesNextMessage() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(discord.send(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return success();
        });
        startListener(publisher, cooldown());
        template.send(EVENTS, DISCORD, ReportDiscordConsumerTest.event(mapper));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        template.send(EVENTS, DISCORD, ReportDiscordConsumerTest.event(mapper));
        var stopped = new CountDownLatch(1);
        listener.stop(stopped::countDown);
        assertThat(stopped.getCount()).isEqualTo(1);
        finish.countDown();
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(template.receive(DISCORD_QUEUE, 3000)).isNotNull();
        assertThat(template.receive(DISCORD_QUEUE, 100)).isNull();
        verify(discord).send(anyString(), anyString());
    }

    @Test
    @DisplayName("ACK 전 연결 손실은 동일 이벤트를 재전달하며 중복 외부 성공을 허용한다")
    void lostAckRedeliversAfterConnectionReset() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(discord.send(anyString(), anyString())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            return success();
        });
        startListener(publisher, cooldown());
        template.send(EVENTS, DISCORD, ReportDiscordConsumerTest.event(mapper));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        connection.resetConnection();
        release.countDown();
        await(() -> calls.get() >= 2, 20);
        listener.stop();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(template.receive(DISCORD_QUEUE, 100)).isNull();
    }

    @Test
    @DisplayName("공유 Redis 원자 max는 짧은 갱신으로 대기를 줄이지 않고 전역 대기를 전파한다")
    void sharedCooldownUsesAtomicMaximumAndExpires() throws Exception {
        ReportDiscordCooldown first = cooldown();
        ReportDiscordCooldown second = new ReportDiscordCooldown(redisTemplate,
            "https://discord.test/api/webhooks/1/token?wait=true");
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var updates = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 20; i++) {
                long delay = i == 3 ? 90000 : 30000;
                updates.add(threads.submit(() -> second.extend(delay, false)));
            }
            for (var update : updates) update.get(3, TimeUnit.SECONDS);
        }
        assertThat(first.remainingMs(0)).isBetween(85000L, 90000L);
        var different = new ReportDiscordCooldown(redisTemplate, "https://discord.test/api/webhooks/2/other");
        assertThat(different.remainingMs(0)).isZero();
        first.extend(120000, true);
        assertThat(different.remainingMs(0)).isBetween(115000L, 120000L);
        assertThat(redisTemplate.keys("report:discord:cooldown:*")).allSatisfy(key -> {
            assertThat(key).doesNotContain("token");
            assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(180000);
        });
    }

    private void await(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private DiscordWebhookClient.DeliveryResult failure() {
        return new DiscordWebhookClient.DeliveryResult(false, false, 0, false, "Temporary");
    }

    private DiscordWebhookClient.DeliveryResult success() {
        return new DiscordWebhookClient.DeliveryResult(true, false, 0, false, "Success");
    }
}
